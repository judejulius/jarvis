package com.jarvis.assistant.ai

import java.net.URI

/** Where an AI endpoint lives: on this phone (loopback), on the local network, or in the cloud. */
object Endpoints {
    private fun parse(url: String): URI? {
        val t = url.trim()
        if (t.isEmpty()) return null
        return runCatching { URI(if ("://" in t) t else "http://$t") }.getOrNull()
    }

    fun host(url: String): String? = parse(url)?.host?.lowercase()

    /** True for a server running on this phone, e.g. llama-server in Termux. */
    fun isLoopback(url: String): Boolean {
        val h = host(url) ?: return false
        return h == "localhost" || h == "::1" || h == "[::1]" || h.startsWith("127.")
    }

    /** True when the endpoint works without the internet: this phone or the local network. */
    fun isLocal(url: String): Boolean {
        if (isLoopback(url)) return true
        val h = host(url) ?: return false
        if (h.endsWith(".local")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    /** `http://127.0.0.1:8080/v1` -> `http://127.0.0.1:8080`. */
    fun origin(url: String): String? {
        val u = parse(url) ?: return null
        val h = u.host ?: return null
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme ?: "http"}://$h$port"
    }
}
