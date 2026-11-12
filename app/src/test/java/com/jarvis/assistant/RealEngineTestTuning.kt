package com.jarvis.assistant

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.voice.Kokoro
import com.jarvis.assistant.voice.ModelDownloader
import com.jarvis.assistant.voice.SpeechModel
import com.jarvis.assistant.voice.VoiceInput
import com.jarvis.assistant.voice.WakeModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.io.File
import java.time.Duration
import java.util.Random

/**
 * Measurements (not pass/fail) for tuning the microphone pipeline with the real engine:
 * wake word hit rates for quiet (far away) speech and false alarms, with and without automatic
 * gain. Run with JARVIS_TUNING=1 ./gradlew testDebugUnitTest -Preal; results are printed.
 *
 * Results (2026-10-15, 18 wake phrases in 6 voices, 36 sound-alikes, light background hiss):
 *   volume 1      no gain 18/18, 0 false   gain 18/18, 0 false
 *   volume 1/8    no gain 13/18, 1 false   gain 15/18, 1 false
 *   volume 1/20   no gain  7/18, 1 false   gain  7/18, 1 false
 *
 * Results (2026-11-12, the same 18 wake phrases and 36 sound-alikes, with [com.jarvis.assistant.voice.MicGain]).
 * Kokoro's speech has an RMS of about 0.07; a phone's plain microphone gives about 0.008 for a normal voice at
 * arm's length and 0.002 from across a room. No false alarms in any run.
 *   volume   quiet room (hiss 0.0003)     noisier (hiss 0.0015)
 *            no gain   gain               no gain   gain
 *   1        17/18     17/18              17/18     16/18
 *   1/8      14/18     16/18              12/18     16/18
 *   1/20     13/18     17/18               8/18     12/18
 *   1/40     11/18     17/18               3/18      3/18
 *   1/80      4/18     12/18               0/18      0/18
 * The earlier gain reacted over seconds and stopped at 8 times, so it did little for a short "Hey Jarvis".
 *
 * Lowering the keyword thresholds ("high sensitivity") didn't help, so there is no such setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealEngineTestTuning {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val native = File(System.getProperty("jarvis.nativeDir") ?: "test-native")
    private val models = File(native, "models")

    @Before
    fun setUp() {
        val lib = File(native, "sherpa-onnx-v1.13.8-linux-x64-jni/lib")
        assumeTrue(File(lib, "libsherpa-onnx-jni.so").exists() && File(models, "kokoro.tar.bz2").exists())
        // Slow (about 10 minutes): only when asked for, with JARVIS_TUNING=1.
        assumeTrue("set JARVIS_TUNING=1 to run", System.getenv("JARVIS_TUNING") != null)
        System.load(File(lib, "libonnxruntime.so").absolutePath)
        System.load(File(lib, "libsherpa-onnx-jni.so").absolutePath)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        ModelDownloader.unpack(File(models, "kokoro.tar.bz2"), Kokoro.modelDir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.unpack(File(models, "kws.tar.bz2"), WakeModel.dir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.prune(WakeModel.dir(ctx), WakeModel.KEEP_FILES)
        ModelDownloader.unpack(File(models, "asr.tar.bz2"), SpeechModel.dir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.prune(SpeechModel.dir(ctx), SpeechModel.KEEP_FILES)
    }

    private val tts by lazy {
        val dir = Kokoro.modelDir(ctx)
        OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = File(dir, "model.int8.onnx").path, voices = File(dir, "voices.bin").path,
                tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path,
            ), numThreads = 4)))
    }

    private fun say(text: String, voice: Int): FloatArray {
        val a = tts.generate(text, voice, 1.0f)
        val ratio = a.sampleRate / 16000.0
        return FloatArray((a.samples.size / ratio).toInt()) { i ->
            val x = i * ratio; val j = x.toInt(); val f = (x - j).toFloat()
            a.samples[j] * (1 - f) + a.samples[minOf(j + 1, a.samples.size - 1)] * f
        }
    }

    private val voices = listOf(5, 6, 9, 10, 1, 7)
    private val wakePhrases = listOf("Hey Jarvis, turn on the flashlight.", "Hey Jarvis, what's the weather?", "Jarvis, call mum.")
    private val soundAlikes = listOf("Hey Travis, how are you?", "The harvest is almost ready.", "Have you seen my car keys?",
        "Jar of biscuits please.", "Hey service desk.", "Charge this phone.", "Hey Jarred, nice to see you.", "Harvey's coming over.",
        "The jars are in the cupboard.", "Nervous about the exam.", "Hey, are you there?", "Davis Cup final.")

    /** Feeds [script] through VoiceInput as fast as it can process it, counting wake-ups. */
    private fun countWakes(script: FloatArray, gain: Boolean): Int {
        var pos = 0
        var done = false
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                Thread.sleep(sizeInShorts * 1000L / 16000 / 4) // 4x real time
                for (i in 0 until sizeInShorts) {
                    val v = if (pos < script.size) script[pos] else 0f
                    audioData[offsetInShorts + i] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    pos++
                }
                if (pos >= script.size) done = true
                return sizeInShorts
            }
        })
        var wakes = 0
        lateinit var voice: VoiceInput
        voice = VoiceInput(ctx) { wakes++; voice.cancelCommand() } // back to waiting for the wake word
        voice.echoCancellation = false
        voice.autoGain = gain
        var loaded = false
        voice.load { loaded = true }
        val looper = shadowOf(Looper.getMainLooper())
        while (!loaded) { looper.idleFor(Duration.ofMillis(20)); Thread.sleep(20) }
        voice.start()
        while (!done) { looper.idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        Thread.sleep(200); looper.idleFor(Duration.ofMillis(200))
        voice.release()
        return wakes
    }

    /** Utterances at [scale] volume, 2.5 s apart, over steady background hiss. */
    private fun script(lines: List<FloatArray>, scale: Float, noise: Float): FloatArray {
        val gap = FloatArray(40000)
        val all = lines.fold(gap) { acc, l -> acc + FloatArray(l.size) { l[it] * scale } + gap }
        val r = Random(7)
        return FloatArray(all.size) { all[it] + (r.nextGaussian() * noise).toFloat() }
    }

    @Test
    fun measure() {
        val wakeAudio = voices.flatMap { v -> wakePhrases.map { say(it, v) } }
        val alikeAudio = voices.take(3).flatMap { v -> soundAlikes.map { say(it, v) } }
        println("TUNING wake utterances=${wakeAudio.size} sound-alikes=${alikeAudio.size}")
        for (gain in listOf(false, true)) {
            // Kokoro's speech has an RMS of about 0.07. A phone's plain microphone gives about 0.008 for a normal voice
            // at arm's length and 0.002 from across a room, over a hiss of about 0.0003 in a quiet room.
            for (scale in listOf(1f, 0.12f, 0.05f, 0.025f, 0.012f)) {
                for (noise in listOf(0.0003f, 0.0015f)) {
                    val hits = countWakes(script(wakeAudio, scale, noise), gain)
                    val falses = countWakes(script(alikeAudio, scale, noise), gain)
                    println("TUNING gain=$gain scale=$scale noise=$noise hits=$hits/${wakeAudio.size} falseAlarms=$falses/${alikeAudio.size}")
                }
            }
        }
    }
}
