package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTraceTest {
    @Test fun logsStageDeltasWithoutAnyText() {
        var t = 1000L
        val lines = ArrayList<String>()
        val trace = LatencyTrace({ t }, { lines += it })
        trace.begin("wake")
        t += 120; trace.mark("heard")
        t += 5; trace.mark("routed")
        t += 90; trace.mark("heard") // repeated stage is ignored
        t += 30; trace.finish("fast")
        assertEquals(listOf("wake fast total=245ms heard=+120ms routed=+5ms"), lines)
    }

    @Test fun finishWithoutBeginIsIgnoredAndBeginResets() {
        var t = 0L
        val lines = ArrayList<String>()
        val trace = LatencyTrace({ t }, { lines += it })
        trace.finish("x")
        trace.mark("a")
        assertTrue(lines.isEmpty())
        trace.begin("one"); t += 10; trace.mark("a")
        trace.begin("two"); t += 7; trace.finish("ok")
        assertEquals(listOf("two ok total=7ms"), lines)
    }
}
