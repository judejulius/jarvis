package com.jarvis.assistant.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import kotlin.math.tanh
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** The "Hey Jarvis" keyword model: a 17 MB download, of which only ~5 MB of int8 files are kept. */
object WakeModel {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    val KEEP_FILES = setOf(ENCODER, DECODER, JOINER, "tokens.txt")
    /** BPE tokens from the model's bpe.model, with per-keyword boost (:) and threshold (#). Tuned: 18/18 hits, 0/60 false alarms. */
    const val KEYWORDS = "▁HE Y ▁JA R VI S :2.0 #0.1 @HEY_JARVIS\n▁JA R VI S :2.0 #0.15 @JARVIS\n"
    /** Listened for while Jarvis speaks: "stop" cuts it off even when the words are drowned by its own voice. */
    const val STOP_KEYWORD = "▁ST O P :2.0 #0.15 @STOP"
    fun dir(context: Context) = File(context.filesDir, "kws")
    fun isInstalled(context: Context) = KEEP_FILES.all { File(dir(context), it).exists() }
}

/** Offline English speech recognition for commands (Kroko streaming zipformer, ~70 MB). */
object SpeechModel {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2"
    val KEEP_FILES = setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    fun dir(context: Context) = File(context.filesDir, "asr")
    fun isInstalled(context: Context) = KEEP_FILES.all { File(dir(context), it).exists() }
}

/**
 * Owns the microphone. Listens for the wake word, then transcribes the command from the same
 * audio stream, so nothing is lost between "Hey Jarvis" and what follows. Everything runs on
 * the phone. Callbacks are delivered on the main thread.
 */
class VoiceInput(private val context: Context, private val onWake: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var running = false
    /** Set by [release]; a model load still in progress must not bring the microphone back. */
    @Volatile private var released = false
    private var thread: Thread? = null

    /** Bumped by every start() and stop(), so a microphone thread that is still winding down can never run beside a new one. */
    @Volatile private var generation = 0

    /**
     * Given the words heard so far, returns how long (ms) the user must stay quiet before the command counts as finished,
     * or null to wait for the normal end-of-speech timeout. Lets "open YouTube" run without waiting a full second.
     */
    @Volatile var earlyAccept: ((String) -> Long?)? = null

    /** Called on the main thread when the microphone stopped working and is being reopened (problem is "failed" or "blocked"), or "ok" once it recovered. */
    @Volatile var onMicProblem: ((problem: String, attempts: Int) -> Unit)? = null

    /** While true, microphone audio is ignored (Jarvis is talking and mustn't hear itself). */
    @Volatile var muted = false

    /** Microphone loudness right now, 0..1, for the island's sound bars. */
    @Volatile var level = 0f
        private set

    @Volatile var wakeEnabled = true

    /** Use the phone's echo cancellation so the user can talk over Jarvis. Applies when the microphone next opens. */
    @Volatile var echoCancellation = true

    // While Jarvis speaks with the microphone open: the words it's saying, so its own echo isn't
    // mistaken for the user. Set by [listenWhileSpeaking].
    @Volatile private var speakingWords: Set<String>? = null
    @Volatile private var speakingSaysName = false
    @Volatile private var speakingSaysStop = false
    @Volatile private var onBargeIn: ((stop: Boolean) -> Unit)? = null
    private var echoStream: OnlineStream? = null // audio thread only
    private var stopStream: OnlineStream? = null // audio thread only
    private var echoFor: Set<String>? = null
    private val sinceSpeaking = ArrayDeque<FloatArray>() // last few seconds while Jarvis speaks
    /** For diagnostics: what was heard while Jarvis was speaking, phrase by phrase. */
    val echoLog = mutableListOf<String>()
    /** For diagnostics: what was last heard while Jarvis was speaking. */
    @Volatile var lastEchoHeard = ""
        private set
    /** Jarvis's words to strip from the start of a command that began by talking over it. */
    private var echoToStrip: Set<String>? = null

    // Automatic gain: quiet (far away) voices are lifted to a level the models handle well.
    @Volatile var autoGain = true
    private var speechRms = TARGET_SPEECH_RMS
    @Volatile var gain = 1f
        private set

    // Command state, touched by the audio thread and main thread.
    private val lock = Any()
    private var command: CommandSession? = null

    /** Text recognised so far in the current command, or "" when not transcribing. */
    val partialText: String get() = synchronized(lock) { command?.text.orEmpty() }

    /**
     * True once the user has made speech-like sound since the wake word. Unlike [partialText] this
     * reacts instantly (recognition lags ~0.5 s behind), so it's what decides whether to say "Yes?".
     */
    @Volatile var speechSinceWake = false
        private set

    // Background noise level, learned while waiting for the wake word, so "speech" adapts to the room.
    @Volatile private var noiseFloor = 0.005f
    // Audio heard between the wake word and the command session starting, so no words are lost.
    private val afterWake = ArrayList<FloatArray>()
    private var capturing = false
    // The last 1.5 s heard while waiting for the wake word. The keyword is only confirmed a moment
    // after it's said, so this is replayed into the transcriber to catch words said straight after it.
    private val recent = ArrayDeque<FloatArray>()

    val canTranscribe get() = recognizer != null

    /** Whether the microphone is open. */
    val isListening get() = running
    val hasWakeWord get() = spotter != null

    /**
     * Keep listening while Jarvis says [spoken], so the user can interrupt by talking (or by saying
     * "Jarvis" or "stop"). [bargeIn] runs on the main thread when they do, with stop = true if they
     * just said "stop"; otherwise the words they said are waiting for the next [listenForCommand].
     */
    fun listenWhileSpeaking(spoken: String, bargeIn: (stop: Boolean) -> Unit) {
        onBargeIn = bargeIn
        speakingSaysName = words(spoken).any { it.length >= 4 && editDistance(it, "jarvis") <= 1 }
        speakingSaysStop = soundsLikeStop(spoken)
        speakingWords = words(spoken).toSet()
        muted = false
    }

    fun stopListeningWhileSpeaking() {
        speakingWords = null
        onBargeIn = null
    }

    /** Loads whichever models are installed. Calls [ready] on the main thread with an error message or null. */
    fun load(ready: (error: String?) -> Unit) {
        if (released) return
        Thread {
            val errors = mutableListOf<String>()
            if (spotter == null && WakeModel.isInstalled(context)) {
                try {
                    val dir = WakeModel.dir(context)
                    File(dir, "jarvis_keywords.txt").writeText(WakeModel.KEYWORDS)
                    spotter = KeywordSpotter(
                        config = KeywordSpotterConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = File(dir, WakeModel.ENCODER).absolutePath,
                                    decoder = File(dir, WakeModel.DECODER).absolutePath,
                                    joiner = File(dir, WakeModel.JOINER).absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                numThreads = 1,
                            ),
                            keywordsFile = File(dir, "jarvis_keywords.txt").absolutePath,
                            keywordsScore = 2.0f,
                            keywordsThreshold = 0.1f,
                        ),
                    )
                } catch (e: Throwable) { // native errors included: report, never crash
                    Log.e(TAG, "wake word model failed to load", e)
                    errors += "The wake word couldn't start. Try downloading it again in Settings."
                }
            }
            if (recognizer == null && SpeechModel.isInstalled(context)) {
                try {
                    val dir = SpeechModel.dir(context)
                    recognizer = OnlineRecognizer(
                        config = OnlineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = File(dir, "encoder.onnx").absolutePath,
                                    decoder = File(dir, "decoder.onnx").absolutePath,
                                    joiner = File(dir, "joiner.onnx").absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                numThreads = 2,
                            ),
                            enableEndpoint = true,
                            endpointConfig = EndpointConfig(
                                rule1 = EndpointRule(false, 2.4f, 0f),
                                rule2 = EndpointRule(true, 1.2f, 0f),
                                rule3 = EndpointRule(false, 0f, 20f),
                            ),
                        ),
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "speech model failed to load", e)
                    errors += "Offline speech recognition couldn't start; using Google's instead."
                }
            }
            if (released) {
                // Turned off while loading: free what was just loaded and stay quiet.
                spotter?.release(); spotter = null
                recognizer?.release(); recognizer = null
                return@Thread
            }
            main.post { if (!released) ready(errors.firstOrNull()) }
        }.start()
    }

    /** Opens the microphone and starts listening (for the wake word, and for commands when asked). */
    fun start() {
        if (released || running || (spotter == null && recognizer == null)) return
        running = true
        val gen = ++generation
        thread = Thread({ loop(gen) }, "jarvis-mic").apply { start() }
    }

    /** Closes the microphone (e.g. so Google's recognizer can use it). */
    fun stop() {
        running = false
        generation++
        thread?.let { if (it !== Thread.currentThread()) it.join(1500) }
        thread = null
    }

    fun release() {
        released = true
        stop()
        cancelCommand()
        spotter?.release(); spotter = null
        recognizer?.release(); recognizer = null
    }

    /**
     * Transcribe what the user says next. [onResult] gets the text, or null if nothing was said
     * within [silenceTimeoutMs]. Requires the speech model ([canTranscribe]).
     */
    fun listenForCommand(silenceTimeoutMs: Long = 7000, onResult: (String?) -> Unit) {
        val asr = recognizer ?: run { onResult(null); return }
        synchronized(lock) {
            command?.stream?.release()
            command = CommandSession(asr.createStream(""), silenceTimeoutMs, onResult, echoToStrip).apply {
                pending.addAll(afterWake) // replay what was said right after the wake word
            }
            afterWake.clear()
            capturing = false
            echoToStrip = null
        }
        start()
    }

    fun cancelCommand() {
        synchronized(lock) { command?.stream?.release(); command = null; capturing = false; afterWake.clear(); echoToStrip = null }
    }

    private class CommandSession(val stream: OnlineStream, val silenceTimeoutMs: Long, val onResult: (String?) -> Unit, val echo: Set<String>?) {
        val pending = ArrayList<FloatArray>()
        var text = ""
        var samples = 0L
        var lastChangeAt = 0L
    }

    private enum class MicExit { STOPPED, NO_PERMISSION, FAILED, BLOCKED }

    @Volatile private var micProblemReported = false

    private fun alive(gen: Int) = running && !released && generation == gen

    private fun isDigitalSilence(pcm: ShortArray, n: Int): Boolean {
        for (i in 0 until n) if (pcm[i].toInt() != 0) return false
        return true
    }

    /**
     * Keeps the microphone open. If Android takes it away (another app, a call, a dead audio object) or silences it, the
     * microphone is reopened with a growing pause, instead of leaving "Hey Jarvis" deaf until the service restarts.
     */
    private fun loop(gen: Int) {
        var failures = 0
        var blocked = 0
        while (alive(gen)) {
            val startedAt = SystemClock.elapsedRealtime()
            val exit = runMic(gen, detectSilence = blocked <= MAX_BLOCKED_RESTARTS)
            if (!alive(gen)) return
            when (exit) {
                MicExit.NO_PERMISSION -> { running = false; return }
                MicExit.STOPPED -> return
                MicExit.FAILED, MicExit.BLOCKED -> {
                    if (SystemClock.elapsedRealtime() - startedAt > 30_000) failures = 0 // it was healthy for a while
                    failures++
                    if (exit == MicExit.BLOCKED) blocked++
                    Log.w(TAG, "microphone restart: $exit (attempt $failures)")
                    if (failures >= 2 || exit == MicExit.BLOCKED) {
                        micProblemReported = true
                        main.post { onMicProblem?.invoke(exit.name.lowercase(), failures) }
                    }
                    var waited = 0L
                    val pause = (500L * failures).coerceAtMost(5000L)
                    while (waited < pause && alive(gen)) { Thread.sleep(100); waited += 100 }
                }
            }
        }
    }

    private fun runMic(gen: Int, detectSilence: Boolean): MicExit {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no microphone permission")
            return MicExit.NO_PERMISSION
        }
        var record: AudioRecord? = null
        var wakeStream: OnlineStream? = null
        var echoCanceller: AcousticEchoCanceler? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // The "voice call" source switches on the phone's echo cancellation, so Jarvis's own voice
            // is largely removed from what the microphone hears and the user can talk over it.
            val source = if (echoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            record = AudioRecord(
                source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 5 * 2),
            )
            if (echoCancellation) echoCanceller = runCatching {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
            }.getOrNull()
            if (record.state != AudioRecord.STATE_INITIALIZED) return MicExit.FAILED
            record.startRecording()
            val pcm = ShortArray(SAMPLE_RATE / 10) // 100 ms
            var errorReads = 0
            var zeroReads = 0
            var reads = 0
            while (alive(gen)) {
                val n = record.read(pcm, 0, pcm.size)
                if (n < 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || ++errorReads >= MAX_READ_ERRORS) return MicExit.FAILED
                    Thread.sleep(20)
                    continue
                }
                if (n == 0) continue
                errorReads = 0
                if (++reads % 10 == 0 && record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return MicExit.FAILED
                if (reads == 10 && micProblemReported) {
                    micProblemReported = false
                    main.post { onMicProblem?.invoke("ok", 0) } // a second of real audio: the microphone is back
                }
                if (detectSilence) {
                    // Android hands a blocked app endless exact zeros instead of an error.
                    if (isDigitalSilence(pcm, n)) { if (++zeroReads >= SILENT_READS) return MicExit.BLOCKED } else zeroReads = 0
                }
                if (muted) { level = 0f; continue }
                val samples = FloatArray(n) { pcm[it] / 32768f }
                val rawLevel = rms(samples)
                applyGain(samples, rawLevel)
                val level = rawLevel * gain
                this.level = (level * 8).coerceAtMost(1f)
                if (level > maxOf(MIN_SPEECH_LEVEL, noiseFloor * gain * 3)) speechSinceWake = true

                val (session, backlog) = synchronized(lock) {
                    val c = command
                    if (c == null && capturing) {
                        if (afterWake.size < 50) afterWake += samples // up to 5 s
                        return@synchronized null to null
                    }
                    c to c?.pending?.toList()?.also { c.pending.clear() }
                }
                if (session != null) {
                    if (!backlog.isNullOrEmpty()) {
                        backlog.forEach { feedCommand(session, it) }
                        // Replayed audio is from the past: the "they've stopped talking" timer starts now.
                        session.lastChangeAt = session.samples
                    }
                    feedCommand(session, samples)
                    continue
                }
                if (capturing) continue
                if (speakingWords == null) noiseFloor = if (rawLevel < 0.05f) noiseFloor * 0.95f + rawLevel * 0.05f else noiseFloor
                recent.addLast(samples)
                while (recent.size > 15) recent.removeFirst()
                if (checkBargeIn(samples)) continue
                val kws = spotter
                if (kws != null && wakeEnabled && !(speakingWords != null && speakingSaysName)) {
                    val stream = wakeStream ?: kws.createStream("").also { wakeStream = it }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        if (kws.getResult(stream).keyword.isNotBlank()) {
                            kws.reset(stream)
                            synchronized(lock) {
                                afterWake.clear()
                                afterWake.addAll(recent) // includes "Jarvis" itself; clean() strips it from the text
                                capturing = recognizer != null
                            }
                            recent.clear()
                            speechSinceWake = false
                            main.post { onWake() }
                            break
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "microphone loop failed", e)
            val session = synchronized(lock) { command.also { command = null } }
            session?.let { s -> main.post { s.onResult(null) } }
            return MicExit.FAILED
        } finally {
            runCatching { record?.stop() }
            record?.release()
            echoCanceller?.release()
            wakeStream?.release()
            echoStream?.release(); echoStream = null; echoFor = null
            stopStream?.release(); stopStream = null
        }
        return MicExit.STOPPED
    }

    /**
     * While Jarvis speaks, transcribes what the microphone hears and compares it with what Jarvis is
     * saying. Words Jarvis isn't saying mean the user is talking: their audio is kept for the
     * command and [onBargeIn] fires. Returns true if it fired.
     */
    private fun checkBargeIn(samples: FloatArray): Boolean {
        val spoken = speakingWords
        if (spoken !== echoFor) { // a new utterance (or none): start afresh
            echoStream?.release(); echoStream = null
            stopStream?.release(); stopStream = null
            sinceSpeaking.clear()
            echoFor = spoken
        }
        if (spoken == null) return false
        val asr = recognizer ?: return false
        sinceSpeaking.addLast(samples)
        while (sinceSpeaking.size > 30) sinceSpeaking.removeFirst() // 3 s
        if (!speakingSaysStop && heardStop(samples)) {
            synchronized(echoLog) { echoLog += "stop [keyword]" }
            val callback = onBargeIn
            speakingWords = null
            onBargeIn = null
            if (callback != null) main.post { callback(true) }
            return true
        }
        val stream = echoStream ?: asr.createStream("").also { echoStream = it }
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (asr.isReady(stream)) asr.decode(stream)
        val heard = asr.getResult(stream).text
        lastEchoHeard = heard
        if (!isBargeIn(heard, spoken)) {
            if (asr.isEndpoint(stream)) {
                if (heard.isNotBlank()) synchronized(echoLog) { echoLog += heard; if (echoLog.size > 20) echoLog.removeAt(0) }
                asr.reset(stream)
            }
            return false
        }
        synchronized(echoLog) { echoLog += "$heard [interrupt]" }
        val callback = onBargeIn
        synchronized(lock) {
            afterWake.clear()
            afterWake.addAll(sinceSpeaking) // what they've said so far (with some of Jarvis's echo, stripped later)
            capturing = true
            echoToStrip = spoken
        }
        speakingWords = null
        onBargeIn = null
        speechSinceWake = true
        if (callback != null) main.post { callback(false) }
        return true
    }

    private fun heardStop(samples: FloatArray): Boolean {
        val kws = spotter ?: return false
        val stream = stopStream ?: kws.createStream(WakeModel.STOP_KEYWORD).also { stopStream = it }
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (kws.isReady(stream)) {
            kws.decode(stream)
            if (kws.getResult(stream).keyword == "STOP") { kws.reset(stream); return true }
        }
        return false
    }

    /** Lifts quiet speech (someone across the room) towards a steady level, without boosting room noise. */
    private fun applyGain(x: FloatArray, raw: Float) {
        if (!autoGain) return
        // Learn how loud the user's voice is, but not from Jarvis's own voice while it speaks.
        if (speakingWords == null) {
            if (raw > noiseFloor * 4 && raw > 0.002f) speechRms = speechRms * 0.9f + raw * 0.1f // learn speech level
            else speechRms += (TARGET_SPEECH_RMS - speechRms) * 0.002f                            // drift back when quiet
        }
        val wanted = (TARGET_SPEECH_RMS / speechRms.coerceAtLeast(1e-4f)).coerceIn(1f, MAX_GAIN)
        val noiseCap = (MAX_NOISE_AFTER_GAIN / noiseFloor.coerceAtLeast(1e-5f)).coerceAtLeast(1f)
        val target = minOf(wanted, noiseCap)
        gain += (target - gain) * (if (target < gain) 0.5f else 0.08f) // turn down fast, up slowly
        if (gain <= 1.01f) return
        for (i in x.indices) x[i] = tanh(x[i] * gain) // soft limit: loud peaks never clip harshly
    }

    private fun feedCommand(session: CommandSession, samples: FloatArray) {
        val asr = recognizer ?: return
        session.stream.acceptWaveform(samples, SAMPLE_RATE)
        while (asr.isReady(session.stream)) asr.decode(session.stream)
        session.samples += samples.size
        val text = clean(stripEcho(asr.getResult(session.stream).text, session.echo))
        if (text != session.text) {
            synchronized(lock) { session.text = text }
            session.lastChangeAt = session.samples
        }
        val ms = { s: Long -> s * 1000 / SAMPLE_RATE }
        val quietFor = ms(session.samples - session.lastChangeAt)
        val done = when {
            text.isEmpty() -> ms(session.samples) >= session.silenceTimeoutMs        // nobody spoke
            asr.isEndpoint(session.stream) -> true                                   // model heard the end
            quietFor >= END_OF_SPEECH_MS -> true                                     // words stopped changing
            earlyAccept?.invoke(text)?.let { quietFor >= it } == true -> true        // a short, complete command
            ms(session.samples) >= MAX_COMMAND_MS -> true
            else -> false
        }
        if (!done) return
        synchronized(lock) { if (command === session) command = null }
        session.stream.release()
        val result = text.ifEmpty { null }
        main.post { session.onResult(result) }
    }

    companion object {
        private const val TAG = "VoiceInput"
        const val SAMPLE_RATE = 16000
        private const val END_OF_SPEECH_MS = 1300L
        private const val MAX_READ_ERRORS = 10
        private const val SILENT_READS = 50          // 5 s of exact zeros = the microphone is blocked
        private const val MAX_BLOCKED_RESTARTS = 2   // after that, assume the phone really outputs digital silence
        private const val TARGET_SPEECH_RMS = 0.06f
        private const val MAX_GAIN = 8f
        private const val MAX_NOISE_AFTER_GAIN = 0.012f

        /** Words that mean "be quiet" when said over Jarvis. */
        val STOP_WORDS = setOf("stop", "wait", "cancel", "quiet", "enough", "pause", "hold", "shush")

        /** Lower-case words, letters and apostrophes only. */
        fun words(text: String): List<String> =
            text.lowercase().split(Regex("\\s+")).map { w -> w.filter { it.isLetter() || it == '\'' } }.filter { it.isNotEmpty() }

        /**
         * Whether a sentence Jarvis is about to say contains something the "stop" keyword could mistake
         * for "stop" ("top", "stock", "start", "story"…). For those sentences the keyword is switched off.
         */
        fun soundsLikeStop(spoken: String): Boolean {
            val ws = words(spoken)
            return ws.withIndex().any { (i, w) ->
                w.startsWith("st") && w.length >= 3 && w[2] in "aeiouy" ||               // stop, stock, start, story, step
                    w.startsWith("top") ||                                                  // top
                    (i > 0 && ws[i - 1].endsWith("s") && w.startsWith("to") && w.length <= 4) // "its top", "bus too"
            }
        }

        /** Whether [word] is something Jarvis said: the same word, nearly, or part of a longer one. */
        private fun isEcho(word: String, spoken: Set<String>) = spoken.any {
            it == word || (word.length >= 4 && editDistance(word, it) <= 1) || (word.length >= 3 && it.length > word.length && word in it)
        }

        /** Whether [heard] is someone talking over Jarvis, rather than Jarvis hearing itself say [spoken]. */
        fun isBargeIn(heard: String, spoken: Set<String>): Boolean {
            val foreign = words(heard).filter { w -> !isEcho(w, spoken) }
            if (foreign.any { it in STOP_WORDS }) return true
            return foreign.count { it.length >= 2 } >= 3
        }

        /** Drops Jarvis's own words (picked up as echo) from the start of [text]. */
        fun stripEcho(text: String, echo: Set<String>?): String {
            if (echo == null) return text
            val ws = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var i = 0
            while (i < ws.size && words(ws[i]).firstOrNull()?.let { w -> isEcho(w, echo) } == true) i++
            return ws.drop(i).joinToString(" ")
        }

        /** "Stop", "never mind" and the like: the user wants Jarvis to stop, not to do something. */
        fun isStopCommand(text: String): Boolean {
            val t = words(text).joinToString(" ")
            return t in setOf("stop", "stop it", "stop that", "stop talking", "cancel", "cancel that", "never mind", "nevermind",
                "shut up", "be quiet", "quiet", "enough", "that's enough", "thats enough", "okay stop", "ok stop", "wait", "hold on", "no stop")
        }
        private const val MIN_SPEECH_LEVEL = 0.02f

        private fun rms(x: FloatArray): Float {
            var sum = 0.0
            for (v in x) sum += v * v
            return Math.sqrt(sum / x.size.coerceAtLeast(1)).toFloat()
        }
        private const val MAX_COMMAND_MS = 20_000L

        private val LEADING_YES = Regex("^(?i)yes[,.!?]*\\s+")

        /**
         * Removes the wake word (and anything before it) from the front of a transcript, plus Jarvis's
         * own "Yes?" prompt. The recogniser spells the wake word inconsistently ("Jarvis", "Arvis",
         * "Page Arvis"), so any of the first three words within one letter of "jarvis" counts.
         */
        fun clean(raw: String): String {
            val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var cut = 0
            for (i in 0 until minOf(3, words.size)) {
                val one = letters(words[i])
                val two = if (i + 1 < words.size) one + letters(words[i + 1]) else ""
                when {
                    one.length >= 4 && editDistance(one, "jarvis") <= 1 -> { cut = i + 1; break }
                    two.length >= 5 && editDistance(two, "jarvis") <= 1 -> { cut = i + 2; break } // "jar vis"
                }
            }
            val rest = words.drop(cut).joinToString(" ").trimStart(',', '.', '!', '?', ' ')
            return LEADING_YES.replace(rest, "").trim().replaceFirstChar { it.uppercase() }
        }

        private fun letters(w: String) = w.lowercase().filter { it.isLetter() }

        fun editDistance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}
