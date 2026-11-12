package com.jarvis.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Kokoro-82M neural voice, run on the phone with sherpa-onnx. Kept light:
 * - one engine for the whole app (service and settings preview share it);
 * - loaded only when needed, and unloaded after [IDLE_UNLOAD_MS] without speech to free ~200 MB;
 * - stock phrases ("Yes?", "On it.") are rendered once and stored on disk, so they play instantly
 *   even while the model is unloaded;
 * - longer text is made in pieces ([SpeechChunks]) while the pieces before play, so speech starts early;
 * - the pieces are sized to how fast this phone makes speech ([SpeechPacer]), so a slower phone
 *   pauses briefly at commas instead of falling silent mid-reply;
 * - the next sentence of a streamed reply is made while the current one plays ([prepare]);
 * - it uses the phone's fast cores only ([VOICE_THREADS]), and shorter pieces on phones with little memory.
 */
object Kokoro {
    private const val TAG = "Kokoro"
    const val SAMPLE_RATE = 24000
    // Kept for 10 minutes so a conversation never pays the reload; freed at once if Android reports memory pressure.
    private const val IDLE_UNLOAD_MS = 600_000L
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
    val STOCK_PHRASES = listOf(
        "Yes?", "On it.", "Please unlock your phone and I'll get started.",
        // short replies to the most common instant commands, ready before they are needed
        "Okay.", "Done.", "Going back.", "Home.", "Flashlight on.", "Flashlight off.", "Paused.", "Playing.",
        "Sorry, I didn't catch that.",
    ) + com.jarvis.assistant.persona.Persona.ALL_GREETINGS + com.jarvis.assistant.persona.Persona.ACKNOWLEDGEMENTS

    fun modelDir(context: Context) = File(context.filesDir, "kokoro")
    fun isInstalled(context: Context) =
        File(modelDir(context), "voices.bin").exists() && File(modelDir(context), "model.int8.onnx").exists()

    // Speech is made on one thread and played on another, so the next piece is made while this one plays.
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kokoro").apply { priority = Thread.MAX_PRIORITY } }
    private val player = Executors.newSingleThreadExecutor { r -> Thread(r, "kokoro-player").apply { priority = Thread.MAX_PRIORITY } }
    private val speed = VoiceSpeed()

    /** The first piece of an utterance is short, so Jarvis starts talking sooner. */
    private const val FIRST_PIECE = 40
    private const val FIRST_PIECE_AHEAD = 80

    /**
     * How long Kokoro takes to make speech, divided by how long the speech lasts, measured on this
     * phone (null until it has spoken). Above 1 it can't keep up with itself and has to pause.
     */
    val realTimeFactor get() = speed.realTimeFactor.takeIf { it > 0f }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var tts: OfflineTts? = null
    private var unloadTask: java.util.concurrent.ScheduledFuture<*>? = null

    /** Diagnostics: samples sent to the speaker for the most recent utterance, and the last error. */
    val lastSamplesPlayed get() = AudioOut.lastSamplesPlayed
    @Volatile var lastError: String? = null
        private set

    /** Set when the model can't load on this phone, so callers fall back to the system voice. */
    @Volatile var failed = false
        private set

    /** Start loading in the background so the next reply has no delay. Safe to call often. */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        if (!isInstalled(app) || failed) return
        worker.execute { engine(app); scheduleUnload() }
    }

    /**
     * Speak [text]. Anything already being said is cut off. [onDone] runs on the main thread when
     * playback finishes (not if it was interrupted by newer speech).
     */
    fun speak(context: Context, text: String, speaker: Int, speed: Float, onDone: () -> Unit) {
        val app = context.applicationContext
        val queuedAt = SystemClock.elapsedRealtime()
        val ticket = AudioOut.interrupt()
        val key = key(text, speaker, speed)
        var wasPrepared = true
        val speech = synchronized(prepared) {
            val ready = prepared.remove(key)?.takeIf { !it.cancelled }
            // Anything else made ahead was for a reply that is no longer being spoken.
            prepared.values.forEach { it.cancelled = true }
            prepared.clear()
            ready
        } ?: start(app, text, speaker, speed, playing = true).also { wasPrepared = false }
        player.execute {
            var firstAudioAt = 0L
            try {
                val out = AudioOut.Playback(ticket)
                speech.pacer.startPlaying()
                while (true) {
                    val chunk = speech.audio.poll(50, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (!AudioOut.isCurrent(ticket)) break
                        continue
                    }
                    if (chunk === END) {
                        out.finish()
                        break
                    }
                    if (firstAudioAt == 0L) firstAudioAt = SystemClock.elapsedRealtime()
                    if (!out.write(chunk)) break
                }
            } catch (e: Throwable) {
                Log.e(TAG, "playback failed", e)
                lastError = "Speaking failed: $e"
            }
            if (!AudioOut.isCurrent(ticket)) speech.cancelled = true // stop making what nobody will hear
            Log.i(TAG, "timing firstAudio=${if (firstAudioAt == 0L) -1 else firstAudioAt - queuedAt}ms " +
                "total=${SystemClock.elapsedRealtime() - queuedAt}ms prepared=$wasPrepared chars=${text.length}")
            if (AudioOut.isCurrent(ticket)) main.post(onDone)
        }
    }

    /**
     * Starts making the speech for [text] without playing it, so that a [speak] of the same text
     * straight after the current speech has it ready. Used for the next sentence of a streamed
     * reply: it is made while the current sentence plays, instead of in a silence after it.
     * Only one utterance is kept ahead; it is dropped if something else is spoken instead.
     */
    fun prepare(context: Context, text: String, speaker: Int, speed: Float) {
        val app = context.applicationContext
        if (!isInstalled(app) || failed) return
        val key = key(text, speaker, speed)
        synchronized(prepared) {
            if (key in prepared) return
            prepared.values.forEach { it.cancelled = true }
            prepared.clear()
            prepared[key] = start(app, text, speaker, speed, playing = false)
        }
    }

    /** Stop speaking now, and drop anything made ahead. */
    fun stop() {
        synchronized(prepared) {
            prepared.values.forEach { it.cancelled = true }
            prepared.clear()
        }
        AudioOut.stop()
    }

    /** One utterance: the voice thread adds audio as it is made, the player thread takes it. [END] marks the end. */
    private class Speech(val pacer: SpeechPacer) {
        val audio = LinkedBlockingQueue<FloatArray>()
        @Volatile var cancelled = false
    }

    private val END = FloatArray(0)
    /** Speech made ahead of being spoken, by [key]. Guarded by itself. */
    private val prepared = HashMap<String, Speech>()

    private fun key(text: String, speaker: Int, speed: Float) = "$speaker|${(speed * 100).toInt()}|$text"

    /** Begins making [text] on the voice thread. [playing] says whether it is being played from the start. */
    private fun start(app: Context, text: String, speaker: Int, speed: Float, playing: Boolean): Speech {
        val stock = text in STOCK_PHRASES
        val pacer = when {
            stock -> SpeechPacer(this.speed, text.length, text.length) // made whole, as it is kept for next time
            // Speech made ahead on a phone that is comfortably fast has time in hand, so its first piece need not be as short.
            else -> SpeechPacer(this.speed, if (!playing && this.speed.realTimeFactor in 0.01f..0.7f) FIRST_PIECE_AHEAD else FIRST_PIECE, maxPiece(app))
        }
        if (playing) pacer.startPlaying()
        val speech = Speech(pacer)
        worker.execute {
            try {
                if (speech.cancelled) return@execute
                val cached = cacheFile(app, text, speaker, speed).takeIf { it.exists() }?.let { readPcm(it) }
                if (cached != null) {
                    speech.audio += cached
                } else {
                    val engine = engine(app) ?: throw IllegalStateException("Kokoro unavailable")
                    val heard = if (stock) ArrayList<FloatArray>() else null
                    val t0 = SystemClock.elapsedRealtime()
                    makePaced(text, pacer, SAMPLE_RATE, out = { speech.audio += it; !speech.cancelled }) { piece, emit ->
                        engine.generateWithCallback(piece, speaker, speed, ChunkCallback { samples ->
                            heard?.add(samples)
                            emit(samples)
                        })
                    }
                    if (heard != null && !speech.cancelled) {
                        writePcm(cacheFile(app, text, speaker, speed), heard.fold(FloatArray(0)) { a, b -> a + b })
                    }
                    Log.i(TAG, "made ${text.length} chars in ${SystemClock.elapsedRealtime() - t0}ms, ahead=${!playing}, " +
                        "${this.speed.realTimeFactor}x its length, ${this.speed.msPerChar}ms per char, $VOICE_THREADS threads")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "speak failed", e)
                lastError = "Speaking failed: $e"
            } finally {
                speech.audio += END
                scheduleUnload()
            }
        }
        return speech
    }

    /** Whether this phone is short of memory (4 GB or less). */
    private fun lowMemory(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || info.totalMem in 1..4_500_000_000L // 0 = unknown; "4 GB" phones report a little less
    }

    // Kokoro needs about 250 MB loaded, and more the longer the piece it is making: 330 MB at 60
    // characters, 400 MB at 100, 440 MB or more at 150. Phones with little memory get shorter pieces.
    private fun maxPiece(context: Context) = if (lowMemory(context)) 90 else 150

    /** Renders the stock phrases for a voice ahead of time (after the model is downloaded or the voice changes). */
    fun prerender(context: Context, speaker: Int, speed: Float) {
        val app = context.applicationContext
        if (!isInstalled(app)) return
        worker.execute {
            for (phrase in STOCK_PHRASES) {
                val f = cacheFile(app, phrase, speaker, speed)
                if (f.exists()) continue
                val engine = engine(app) ?: return@execute
                runCatching { writePcm(f, engine.generate(phrase, speaker, speed).samples) }
            }
            scheduleUnload()
        }
    }

    /**
     * Receives each generated sentence. This must be a real class, not a lambda: sherpa-onnx's native
     * code looks up `invoke(float[]): Integer` by name, and Kotlin 2 compiles lambdas without that method
     * (calling it then fails with NoSuchMethodError and no audio plays).
     */
    private class ChunkCallback(private val onChunk: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (onChunk(samples)) 1 else 0 // 0 = stop generating
    }

    private fun engine(context: Context): OfflineTts? {
        tts?.let { return it }
        if (failed) return null
        return try {
            val dir = modelDir(context)
            val t0 = SystemClock.elapsedRealtime()
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(dir, "model.int8.onnx").absolutePath,
                            voices = File(dir, "voices.bin").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = VOICE_THREADS, // the phone's fast cores
                    ),
                    maxNumSentences = 1, // stream one sentence at a time
                ),
            ).also {
                tts = it
                Log.i(TAG, "loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            }
        } catch (e: Throwable) { // native errors included: never crash, fall back to the system voice
            Log.e(TAG, "load failed", e)
            lastError = "Loading failed: $e"
            failed = true
            null
        }
    }

    /** Frees the voice model now (called when Android is short of memory). It reloads on the next reply. */
    fun unload() {
        worker.execute {
            unloadTask?.cancel(false)
            tts?.release(); tts = null
            Log.i(TAG, "unloaded: low memory")
        }
    }

    private fun scheduleUnload() {
        unloadTask?.cancel(false)
        unloadTask = worker.schedule({
            tts?.release(); tts = null
            Log.i(TAG, "unloaded after idle")
        }, IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
    }

    private fun cacheFile(context: Context, text: String, speaker: Int, speed: Float): File =
        File(context.cacheDir, "kokoro-phrases/${speaker}_${(speed * 100).toInt()}_${text.hashCode().toUInt()}.pcm")

    private fun writePcm(f: File, samples: FloatArray) {
        f.parentFile?.mkdirs()
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.writeInt(samples.size)
            samples.forEach { out.writeFloat(it) }
        }
    }

    private fun readPcm(f: File): FloatArray? = runCatching {
        DataInputStream(f.inputStream().buffered()).use { inp -> FloatArray(inp.readInt()) { inp.readFloat() } }
    }.getOrNull()
}
