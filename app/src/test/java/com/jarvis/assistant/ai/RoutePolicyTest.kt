package com.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyTest {
    private val local = Candidate("qwen", true)
    private val cloudA = Candidate("groq", false)
    private val cloudB = Candidate("gemini", false)
    private val all = listOf(cloudA, local, cloudB)

    private fun ids(l: List<Candidate>) = l.map { it.id }

    @Test fun offlineModeUsesOnlyLocal() {
        assertEquals(listOf("qwen"), ids(RoutePolicy.order(all, AiMode.OFFLINE, true, TaskKind.CHAT, "hi")))
        assertEquals(emptyList<String>(), ids(RoutePolicy.order(listOf(cloudA), AiMode.OFFLINE, true, TaskKind.CHAT, "hi")))
    }

    @Test fun onlineModePrefersCloudWhenReachable() {
        assertEquals(listOf("groq", "gemini", "qwen"), ids(RoutePolicy.order(all, AiMode.ONLINE, true, TaskKind.CHAT, "hi")))
        assertEquals(listOf("qwen"), ids(RoutePolicy.order(all, AiMode.ONLINE, false, TaskKind.CHAT, "hi")))
    }

    @Test fun autoWithoutInternetFallsBackToLocal() {
        assertEquals(listOf("qwen"), ids(RoutePolicy.order(all, AiMode.AUTO, false, TaskKind.CHAT, "explain gravity")))
        // nothing local configured: still try the cloud rather than give up silently
        assertEquals(listOf("groq"), ids(RoutePolicy.order(listOf(cloudA), AiMode.AUTO, false, TaskKind.CHAT, "hi")))
    }

    @Test fun autoSendsPhoneAutomationToTheStrongerCloudModel() {
        assertEquals("groq", ids(RoutePolicy.order(all, AiMode.AUTO, true, TaskKind.AGENT, "send a message"))[0])
    }

    @Test fun autoKeepsShortSimpleChatLocal() {
        assertEquals("qwen", ids(RoutePolicy.order(all, AiMode.AUTO, true, TaskKind.CHAT, "what is two plus two"))[0])
    }

    @Test fun autoSendsHardCurrentOrHindiChatToCloud() {
        assertFalse(RoutePolicy.preferLocalForChat("explain how photosynthesis works"))
        assertFalse(RoutePolicy.preferLocalForChat("what is the weather today"))
        assertFalse(RoutePolicy.preferLocalForChat("मुझे एक कहानी सुनाओ"))
        assertFalse(RoutePolicy.preferLocalForChat("mujhe batao ye kya hai"))
        assertFalse(RoutePolicy.preferLocalForChat("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen"))
        assertTrue(RoutePolicy.preferLocalForChat("how are you"))
        assertEquals("groq", ids(RoutePolicy.order(all, AiMode.AUTO, true, TaskKind.CHAT, "explain gravity"))[0])
    }

    @Test fun trippedProvidersAreDemotedNotDropped() {
        var now = 0L
        val breaker = CircuitBreaker({ now })
        breaker.failure("groq")
        assertEquals(listOf("gemini", "qwen", "groq").sorted(), ids(RoutePolicy.order(all, AiMode.ONLINE, true, TaskKind.CHAT, "x", breaker)).sorted())
        assertEquals("groq", ids(RoutePolicy.order(all, AiMode.ONLINE, true, TaskKind.CHAT, "x", breaker)).last())
        breaker.failure("gemini"); breaker.failure("qwen")
        assertEquals(3, RoutePolicy.order(all, AiMode.ONLINE, true, TaskKind.CHAT, "x", breaker).size)
    }

    @Test fun breakerBacksOffAndRecovers() {
        var now = 0L
        val b = CircuitBreaker({ now }, baseMs = 1000, maxMs = 4000)
        assertFalse(b.isOpen("a"))
        b.failure("a")
        assertTrue(b.isOpen("a"))
        now = 1001
        assertFalse(b.isOpen("a"))
        b.failure("a") // second failure: 2000 ms
        now += 1500
        assertTrue(b.isOpen("a"))
        now += 600
        assertFalse(b.isOpen("a"))
        b.failure("a"); b.failure("a"); b.failure("a") // capped at 4000 ms
        now += 4001
        assertFalse(b.isOpen("a"))
        b.failure("a")
        b.success("a")
        assertFalse(b.isOpen("a"))
    }

    @Test fun startThrottleStopsRestartStorms() {
        var now = 0L
        val t = StartThrottle({ now }, cooldownMs = 1000, maxStarts = 2, windowMs = 10_000)
        assertTrue(t.tryAcquire())
        assertFalse(t.tryAcquire())
        now = 1500
        assertTrue(t.tryAcquire())
        now = 3000
        assertFalse(t.tryAcquire())
        now = 12_000
        assertTrue(t.tryAcquire())
    }

    @Test fun endpointClassification() {
        assertTrue(Endpoints.isLoopback("http://localhost:8080/v1"))
        assertTrue(Endpoints.isLoopback("http://127.0.0.1:8080/v1"))
        assertTrue(Endpoints.isLoopback("localhost:8080/v1"))
        assertFalse(Endpoints.isLoopback("https://api.groq.com/openai/v1"))
        assertTrue(Endpoints.isLocal("http://192.168.1.5:1234/v1"))
        assertFalse(Endpoints.isLoopback("http://192.168.1.5:1234/v1"))
        assertFalse(Endpoints.isLocal("https://openrouter.ai/api/v1"))
        assertEquals("http://127.0.0.1:8080", Endpoints.origin("http://127.0.0.1:8080/v1"))
        assertEquals("http://localhost:8080", Endpoints.origin("localhost:8080/v1"))
    }

    @Test fun modeParsing() {
        assertEquals(AiMode.OFFLINE, AiMode.parse("offline"))
        assertEquals(AiMode.AUTO, AiMode.parse(null))
        assertEquals(AiMode.AUTO, AiMode.parse("nonsense"))
    }
}
