package com.jarvis.assistant.ai

import android.content.Context
import android.os.SystemClock
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.Settings
import com.jarvis.assistant.memory.MemoryStore

/**
 * Online / offline / automatic routing between cloud providers and the local model, with a circuit breaker so a dead
 * provider is skipped instead of waited on, and with the local server started on demand.
 */
class AiRouter(
    private val context: Context,
    private val settings: Settings,
    private val server: LocalServerManager,
    private val memory: () -> MemoryStore?,
) : ModelGate {
    private val breaker = CircuitBreaker(SystemClock::elapsedRealtime)

    override fun plan(task: String, kind: TaskKind): List<ModelProfile> {
        val all = settings.modelsInOrder().let { if (settings.fallbackEnabled) it else it.take(1) }
        val byId = all.associateBy { it.id }
        val candidates = all.map { Candidate(it.id, Endpoints.isLocal(it.baseUrl)) }
        val ordered = RoutePolicy.order(candidates, settings.aiMode, Connectivity.isOnline(context), kind, task, breaker)
        return ordered.mapNotNull { byId[it.id] }
    }

    override fun ready(profile: ModelProfile, hasAlternative: Boolean, status: (String) -> Unit): Boolean {
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

    /** Why the local model could not be used, for the spoken error message. */
    fun localFailureHint(): String = server.explainFailure()
}
