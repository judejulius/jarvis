package com.jarvis.assistant.ai

import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.Settings

/**
 * Decides which AI models a request may use and in which order, and learns from how they behave.
 * [SettingsModelGate] keeps the original behaviour (your saved order); [AiRouter] adds online/offline/auto routing.
 */
interface ModelGate {
    /** Profiles to try for [task], best first. */
    fun plan(task: String, kind: TaskKind): List<ModelProfile>

    /**
     * Makes [profile] usable (for the on-phone server: starts it if needed). Returns false to skip this profile.
     * [hasAlternative] says whether another profile can be tried instead of waiting. Runs on a worker thread.
     */
    fun ready(profile: ModelProfile, hasAlternative: Boolean, status: (String) -> Unit): Boolean = true

    /** Called after each attempt so repeated failures can be skipped for a while. */
    fun report(profile: ModelProfile, ok: Boolean) {}

    /** Notes the user asked Jarvis to keep that are safe to give [profile] (empty if none). */
    fun memoryContext(task: String, profile: ModelProfile): String = ""
}

class SettingsModelGate(private val settings: Settings) : ModelGate {
    override fun plan(task: String, kind: TaskKind): List<ModelProfile> =
        settings.modelsInOrder().let { if (settings.fallbackEnabled) it else it.take(1) }
}
