package com.jarvis.assistant.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What a quick `GET /health` says about a local model server. No Android classes, so it is unit-tested. */
object ServerHealth {
    enum class State { OK, LOADING, DOWN }

    /** llama-server answers 200 when ready and 503 while the model loads. Servers without /health (404) are alive. */
    fun check(origin: String, timeoutMs: Int = 700): State {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("$origin/health").openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            when (c.responseCode) {
                in 200..299, 404, 405 -> State.OK
                503 -> State.LOADING
                else -> State.DOWN
            }
        } catch (_: IOException) {
            State.DOWN
        } catch (_: RuntimeException) {
            State.DOWN
        } finally {
            conn?.disconnect()
        }
    }
}
