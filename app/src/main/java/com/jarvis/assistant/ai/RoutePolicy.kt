package com.jarvis.assistant.ai

data class Candidate(val id: String, val local: Boolean)

/**
 * Remembers which providers failed recently so Jarvis stops waiting on them. A failed provider is skipped for
 * [baseMs], doubling after each further failure up to [maxMs]; one success clears it.
 */
class CircuitBreaker(
    private val now: () -> Long,
    private val baseMs: Long = 30_000L,
    private val maxMs: Long = 300_000L,
) {
    private class State(var failures: Int = 0, var openUntil: Long = 0L)

    private val states = HashMap<String, State>()

    @Synchronized
    fun isOpen(id: String): Boolean = (states[id]?.openUntil ?: 0L) > now()

    @Synchronized
    fun failure(id: String) {
        val s = states.getOrPut(id) { State() }
        s.failures++
        val backoff = (baseMs shl (s.failures - 1).coerceAtMost(10)).coerceAtMost(maxMs)
        s.openUntil = now() + backoff
    }

    @Synchronized
    fun success(id: String) {
        states.remove(id)
    }
}

/** Stops the app from spamming start requests at Termux: one per [cooldownMs], at most [maxStarts] per [windowMs]. */
class StartThrottle(
    private val now: () -> Long,
    private val cooldownMs: Long = 20_000L,
    private val maxStarts: Int = 4,
    private val windowMs: Long = 600_000L,
) {
    private val starts = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        while (starts.isNotEmpty() && t - starts.first() > windowMs) starts.removeFirst()
        if (starts.isNotEmpty() && t - starts.last() < cooldownMs) return false
        if (starts.size >= maxStarts) return false
        starts.addLast(t)
        return true
    }

    @Synchronized
    fun reset() = starts.clear()
}

/** Decides which model profiles to try, and in which order. Pure logic, so it is unit-tested. */
object RoutePolicy {
    private val CURRENT_INFO = Regex(
        """\b(news|weather|score|scores|price|prices|stock|stocks|today|tonight|tomorrow|yesterday|latest|current|currently|""" +
            """right now|trending|result|results|aaj|kal|abhi|mausam|khabar|samachar|rate)\b""",
    )
    private val COMPLEX = Regex(
        """\b(explain|why|how does|how do|write|code|translate|summari[sz]e|compare|essay|story|poem|""" +
            """difference between|step by step|analy[sz]e|plan|recommend|samjhao|likho)\b""",
    )
    private val HINGLISH = setOf(
        "hai", "hain", "kya", "kaise", "kaisa", "kyun", "kyu", "kaun", "kab", "kahan", "mujhe", "mera", "meri", "mere",
        "batao", "bata", "aap", "tum", "tumhe", "hoon", "nahi", "nahin", "aur", "mein", "karo", "kitna", "kitne", "kuch",
        "hota", "hoti", "sakte", "sakta", "chahiye",
    )

    /**
     * Short, simple English chat can be answered by the small local model. Anything that needs current facts, real
     * reasoning, or Hindi/Hinglish goes to a stronger cloud model when the internet is available.
     */
    fun preferLocalForChat(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return true
        if (t.any { it in '\u0900'..'\u097F' }) return false
        val words = t.split(Regex("\\s+"))
        if (words.size > 14) return false
        if (CURRENT_INFO.containsMatchIn(t) || COMPLEX.containsMatchIn(t)) return false
        if (words.count { it.trim(',', '.', '?', '!') in HINGLISH } >= 2) return false
        return true
    }

    fun order(
        candidates: List<Candidate>,
        mode: AiMode,
        online: Boolean,
        kind: TaskKind,
        text: String,
        breaker: CircuitBreaker? = null,
    ): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val local = candidates.filter { it.local }
        val cloud = candidates.filter { !it.local }
        val ordered = when (mode) {
            AiMode.OFFLINE -> local
            AiMode.ONLINE -> if (online) cloud + local else local.ifEmpty { cloud }
            AiMode.AUTO -> when {
                !online -> local.ifEmpty { cloud }
                kind == TaskKind.AGENT -> cloud + local
                preferLocalForChat(text) -> local + cloud
                else -> cloud + local
            }
        }
        if (breaker == null) return ordered
        // A tripped breaker only demotes a provider. If everything is tripped, all of them are still tried.
        return ordered.filterNot { breaker.isOpen(it.id) } + ordered.filter { breaker.isOpen(it.id) }
    }
}
