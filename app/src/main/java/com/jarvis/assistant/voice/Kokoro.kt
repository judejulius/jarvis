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
import java.util.concurrent.TimeUnit

/**
 * Kokoro-82M neural voice, run on the phone with sherpa-onnx. Kept light:
 * - one engine for the whole app (service and settings preview share it);
 * - loaded only when needed, and unloaded after [IDLE_UNLOAD_MS] without speech to free ~200 MB;
 * - stock phrases ("Yes?", "On it.") are rendered once and stored on disk, so they play instantly
 *   even while the model is unloaded;
 * - longer text streams sentence by sentence, so speech starts before the whole reply is generated.
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
    )

    fun modelDir(context: Context) = File(context.filesDir, "kokoro")
    fun isInstalled(context: Context) =
        File(modelDir(context), "voices.bin").exists() && File(modelDir(context), "model.int8.onnx").exists()

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kokoro").apply { priority = Thread.MAX_PRIORITY } }
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
        worker.execute {
            val startedAt = SystemClock.elapsedRealtime()
            var generationMs = 0L
            var cachedAudio = false
            var playbackStarted = false
            try {
                val player = AudioOut.Playback(ticket)
                val cached = cacheFile(app, text, speaker, speed).takeIf { it.exists() }?.let { readPcm(it) }
                if (cached != null) {
                    cachedAudio = true
                    playbackStarted = true
                    player.write(cached)
                } else {
                    val engine = engine(app) ?: throw IllegalStateException("Kokoro unavailable")
                    val generationStartedAt = SystemClock.elapsedRealtime()
                    val audio = engine.generateWithCallback(text, speaker, speed, ChunkCallback(player))
                    generationMs = SystemClock.elapsedRealtime() - generationStartedAt
                    playbackStarted = true
                    if (text in STOCK_PHRASES && AudioOut.isCurrent(ticket)) writePcm(cacheFile(app, text, speaker, speed), audio.samples)
                }
                player.finish()
            } catch (e: Throwable) {
                Log.e(TAG, "speak failed", e)
                lastError = "Speaking failed: $e"
            }
            val completedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "timing queue=${startedAt - queuedAt}ms generation=${generationMs}ms " +
                "total=${completedAt - queuedAt}ms cached=$cachedAudio started=$playbackStarted")
            scheduleUnload()
            if (AudioOut.isCurrent(ticket)) main.post(onDone)
        }
    }

    /** Stop speaking now. */
    fun stop() = AudioOut.stop()

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
    private class ChunkCallback(private val player: AudioOut.Playback) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (player.write(samples)) 1 else 0 // 0 = stop generating
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
                        numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
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
