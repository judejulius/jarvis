package com.jarvis.assistant.util

/**
 * Measures where time goes between "Hey Jarvis" and the first spoken answer. Logs stage names and milliseconds only,
 * never what was said. One trace is active at a time; [begin] starts a new one.
 */
class LatencyTrace(private val now: () -> Long, private val log: (String) -> Unit) {
    private val marks = ArrayList<Pair<String, Long>>()
    private var name = ""
    private var start = 0L
    private var active = false

    @Synchronized
    fun begin(name: String) {
        this.name = name
        marks.clear()
        start = now()
        active = true
    }

    /** Records that [stage] was reached. Ignored when no trace is active or the stage was already recorded. */
    @Synchronized
    fun mark(stage: String) {
        if (!active || marks.any { it.first == stage }) return
        marks += stage to now()
    }

    /** Logs the trace as one line and ends it. */
    @Synchronized
    fun finish(outcome: String) {
        if (!active) return
        active = false
        val end = now()
        var prev = start
        val parts = marks.joinToString(" ") { (stage, t) ->
            val d = t - prev
            prev = t
            "$stage=+${d}ms"
        }
        log("$name $outcome total=${end - start}ms${if (parts.isEmpty()) "" else " $parts"}")
        marks.clear()
    }
}
