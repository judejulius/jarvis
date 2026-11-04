package com.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ServerHealthTest {
    /** A one-shot HTTP server on a free port that answers every request with [status]. */
    private fun serve(status: String, body: String = ""): Pair<ServerSocket, String> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        out.flush()
                    }
                } catch (_: Exception) {
                    // server closed
                }
            }
        }
        return server to "http://127.0.0.1:${server.localPort}"
    }

    @Test fun readyServerIsOk() {
        val (s, origin) = serve("200 OK", "{\"status\":\"ok\"}")
        try { assertEquals(ServerHealth.State.OK, ServerHealth.check(origin, 2000)) } finally { s.close() }
    }

    @Test fun loadingModelIsReportedAsLoading() {
        val (s, origin) = serve("503 Service Unavailable", "{}")
        try { assertEquals(ServerHealth.State.LOADING, ServerHealth.check(origin, 2000)) } finally { s.close() }
    }

    @Test fun serverWithoutHealthEndpointCountsAsAlive() {
        val (s, origin) = serve("404 Not Found", "")
        try { assertEquals(ServerHealth.State.OK, ServerHealth.check(origin, 2000)) } finally { s.close() }
    }

    @Test fun serverErrorIsDown() {
        val (s, origin) = serve("500 Internal Server Error", "")
        try { assertEquals(ServerHealth.State.DOWN, ServerHealth.check(origin, 2000)) } finally { s.close() }
    }

    @Test fun nothingListeningIsDown() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(ServerHealth.State.DOWN, ServerHealth.check("http://127.0.0.1:$free", 500))
    }
}
