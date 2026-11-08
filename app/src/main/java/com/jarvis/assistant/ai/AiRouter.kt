package com.jarvis.assistant.ai

import android.content.Context
import android.os.SystemClock
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.Settings
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.runtime.EmbeddedRuntime
import com.jarvis.assistant.runtime.RuntimeConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Online / offline / automatic routing between cloud providers and the local model, with a circuit breaker so a dead
 * provider is skipped instead of waited on, and with the local server started on demand.
 */
class AiRouter(
    private val context: Context,
    private val settings: Settings,
    private val server: LocalServerManager,
    private val memory: () -> MemoryStore?,
    private val runtime: EmbeddedRuntime? = null,
) : ModelGate {
    private val breaker = CircuitBreaker(SystemClock::elapsedRealtime)
    private val startingInBackground = AtomicBoolean(false)

    /** The model server that ships inside the app, as a profile. It is tried before the user's own local profiles. */
    private fun builtin(): ModelProfile? {
        val rt = runtime ?: return null
        if (!settings.embeddedRuntimeEnabled || !rt.isSupported()) return null
        return ModelProfile(
            id = RuntimeConfig.BUILTIN_PROFILE_ID, name = "On-device Qwen", providerId = "custom",
            baseUrl = rt.baseUrl(), apiKey = rt.apiKey(), model = RuntimeConfig.MODEL_NAME, effort = "",
        )
    }

    override fun plan(task: String, kind: TaskKind): List<ModelProfile> {
        val saved = settings.modelsInOrder().let { if (settings.fallbackEnabled) it else it.take(1) }
        val all = listOfNotNull(builtin()) + saved
        val byId = all.associateBy { it.id }
        val candidates = all.map { Candidate(it.id, Endpoints.isLocal(it.baseUrl)) }
        val ordered = RoutePolicy.order(candidates, settings.aiMode, Connectivity.isOnline(context), kind, task, breaker)
        return ordered.mapNotNull { byId[it.id] }
    }

    override fun ready(profile: ModelProfile, hasAlternative: Boolean, status: (String) -> Unit): Boolean {
        if (profile.id == RuntimeConfig.BUILTIN_PROFILE_ID) return builtinReady(hasAlternative, status)
        if (!Endpoints.isLoopback(profile.baseUrl)) return true
        return server.prepare(profile.baseUrl, hasAlternative, status)
    }

    override fun report(profile: ModelProfile, ok: Boolean) {
        if (ok) breaker.success(profile.id) else breaker.failure(profile.id)
    }

    override fun memoryContext(task: String, profile: ModelProfile): String = try {
        memory()?.promptContext(task, Endpoints.isLocal(profile.baseUrl), settings.shareMemoryWithCloud).orEmpty()
    } catch (_: RuntimeException) {
        ""
    }

    private fun builtinReady(hasAlternative: Boolean, status: (String) -> Unit): Boolean {
        val rt = runtime ?: return false
        if (!rt.hasModel()) {
            rt.startDownloadIfNeeded(settings.downloadModelOnMobile) // returns at once; progress shows in the status line
            status(rt.status.detail.ifBlank { "The on-device model is still downloading." })
            return false
        }
        if (rt.health() == ServerHealth.State.OK) { rt.touch(); return true }
        if (hasAlternative) {
            // Another model can answer now; bring this one up in the background for the next question.
            if (startingInBackground.compareAndSet(false, true)) {
                Thread({ try { rt.ensureRunning() } finally { startingInBackground.set(false) } }, "jarvis-runtime-start")
                    .apply { isDaemon = true }.start()
            }
            return false
        }
        status("Starting my on-device model…")
        return rt.ensureRunning()
    }

    /** Why the local model could not be used, for the spoken error message. */
    fun localFailureHint(): String {
        val rt = runtime
        if (rt != null && builtin() != null) {
            val d = rt.status.detail
            if (d.isNotBlank()) return d
        }
        return server.explainFailure()
    }
}
