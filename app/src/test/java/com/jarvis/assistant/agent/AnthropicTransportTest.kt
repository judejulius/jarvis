package com.jarvis.assistant.agent

import com.jarvis.assistant.ModelProfile
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnthropicTransportTest {
    @Test fun messagesUseOnlyTheFixedOriginAndCarryTheSdkBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msg_test","type":"message","role":"assistant","model":"m","content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":1}}"""))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val req = chain.request()
                assertEquals("https://api.anthropic.com/v1/messages", req.url.toString())
                chain.proceed(req.newBuilder().url(server.url(req.url.encodedPath)).build())
            }.build()
            val cfg = ModelProfile(name = "Claude", providerId = "anthropic", baseUrl = AnthropicTransport.ORIGIN, apiKey = "dummy", model = "m", effort = "").toConfig()
            val session = AnthropicSession(cfg, "Test", emptyList(), AnthropicTransport(cfg.apiKey, client))
            assertEquals("OK", session.send("hello", emptyList()).text)
            val request = server.takeRequest()
            assertEquals("dummy", request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            assertTrue(request.body.readUtf8().contains("hello"))
        }
    }
}
