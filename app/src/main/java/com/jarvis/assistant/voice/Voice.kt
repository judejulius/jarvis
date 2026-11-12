package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.CLOUD_VOICES
import com.jarvis.assistant.Settings

interface VoiceEngine {
    /** Speak [text], replacing anything currently being said. [onDone] runs on the main thread. */
    fun speak(text: String, onDone: () -> Unit)
    fun shutdown()
}

/**
 * The voice Jarvis talks with: a cloud voice or Kokoro when one is selected and available,
 * otherwise Android's built-in voice. If a cloud voice fails (no internet, bad key), that sentence
 * is spoken locally instead, so Jarvis never goes silent. Settings are read on every call.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val settings = Settings(app)
    private val system by lazy { SystemVoice(app) }
    private val cloud by lazy { CloudVoice() }
    private var usedSystem = false
    private var usedCloud = false
    /** Whether the current speech can report its loudness (everything except Android's voice). */
    @Volatile private var measurable = false

    val usingKokoro get() = settings.voiceEngine == "kokoro" && kokoroReady

    private val kokoroReady get() = Kokoro.isInstalled(app) && !Kokoro.failed

    /** The cloud voice's settings, or null if a cloud voice isn't selected or has no key yet. */
    private fun cloudConfig() = CLOUD_VOICES.firstOrNull { it.id == settings.voiceEngine }?.let { preset ->
        settings.cloudVoice(preset.id).takeIf { !preset.needsKey || it.apiKey.isNotBlank() }
    }

    /** The last problem with the chosen voice, for Settings. */
    val lastError: String?
        get() = if (cloudConfig() != null || settings.voiceEngine in CLOUD_VOICES.map { it.id }) cloud.lastError else Kokoro.lastError

    fun speak(text: String, onDone: () -> Unit = {}) {
        val config = cloudConfig()
        if (config != null) {
            usedCloud = true
            measurable = true
            cloud.speak(config, text, settings.voiceSpeed, onDone) { speakLocally(text, onDone) }
        } else {
            speakLocally(text, onDone)
        }
    }

    private fun speakLocally(text: String, onDone: () -> Unit) {
        // Kokoro when chosen, or as the fallback for a cloud voice; otherwise Android's voice.
        if (kokoroReady && settings.voiceEngine != "system") {
            measurable = true
            Kokoro.speak(app, text, settings.kokoroSpeaker, settings.voiceSpeed, onDone)
        } else {
            usedSystem = true
            measurable = false
            system.speak(text, onDone)
        }
    }

    /**
     * Says that [text] will be spoken straight after the current speech, so the voice can make it
     * meanwhile. Only Kokoro uses this; the other voices start quickly enough without it.
     */
    fun prepare(text: String) {
        if (cloudConfig() == null && kokoroReady && settings.voiceEngine != "system") {
            Kokoro.prepare(app, text, settings.kokoroSpeaker, settings.voiceSpeed)
        }
    }

    /** Load the voice now so the next reply starts without delay. */
    fun warmUp() { if (usingKokoro) Kokoro.warmUp(app) }

    fun stop() {
        Kokoro.stop() // stops the shared speaker output, whichever voice is using it
        if (usedCloud) cloud.stop()
        if (usedSystem) system.stop()
    }

    /** Loudness of the speech playing now (0..1), or null if this voice can't tell. */
    fun level(): Float? = if (measurable) AudioOut.level() else null

    fun shutdown() {
        stop()
        if (usedSystem) system.shutdown()
    }
}
