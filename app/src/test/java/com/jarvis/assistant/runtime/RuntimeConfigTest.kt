package com.jarvis.assistant.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigTest {
    @Test fun commandLineIsLoopbackOnlyAndProtectedByAKey() {
        val a = RuntimeConfig.args("/m.gguf", 18080, "k3y", 4)
        assertEquals("127.0.0.1", a[a.indexOf("--host") + 1])
        assertEquals("k3y", a[a.indexOf("--api-key") + 1])
        assertEquals("1", a[a.indexOf("-np") + 1])
        assertEquals("4096", a[a.indexOf("-c") + 1])
        assertTrue("--no-webui" in a)
        assertFalse("0.0.0.0" in a)
    }

    @Test fun threadsFollowTheCoreCount() {
        assertEquals(4, RuntimeConfig.threadsFor(8))
        assertEquals(3, RuntimeConfig.threadsFor(6))
        assertEquals(2, RuntimeConfig.threadsFor(2))
        assertEquals(4, RuntimeConfig.threadsFor(16))
    }

    @Test fun apiKeysAreLongAndDifferent() {
        val a = RuntimeConfig.newApiKey(); val b = RuntimeConfig.newApiKey()
        assertEquals(48, a.length)
        assertTrue(a != b)
    }

    @Test fun supervisorDecisions() {
        val min = 60_000L
        assertEquals(RuntimePolicy.Action.NONE, RuntimePolicy.next(true, 0, 1000, 20 * min))
        assertEquals(RuntimePolicy.Action.NONE, RuntimePolicy.next(true, 2, 1000, 20 * min))
        assertEquals(RuntimePolicy.Action.KILL_HUNG, RuntimePolicy.next(true, 3, 1000, 20 * min))
        assertEquals(RuntimePolicy.Action.RESTART, RuntimePolicy.next(false, 0, 1000, 20 * min))
        assertEquals(RuntimePolicy.Action.STOP_IDLE, RuntimePolicy.next(true, 0, 21 * min, 20 * min))
        assertEquals(RuntimePolicy.Action.STOP_IDLE, RuntimePolicy.next(false, 0, 21 * min, 20 * min)) // crashed while nobody was using it: stay down
        assertEquals(RuntimePolicy.Action.NONE, RuntimePolicy.next(true, 0, 999 * min, 0)) // keep-warm 0 = never stop for idleness
    }

    @Test fun restartBackoffGrowsAndGivesUp() {
        assertEquals(2000L, RuntimePolicy.restartDelayMs(1))
        assertEquals(4000L, RuntimePolicy.restartDelayMs(2))
        assertEquals(60_000L, RuntimePolicy.restartDelayMs(9))
        assertFalse(RuntimePolicy.giveUp(4))
        assertTrue(RuntimePolicy.giveUp(5))
    }
}
