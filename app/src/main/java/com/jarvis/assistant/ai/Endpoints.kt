package com.jarvis.assistant.ai

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where an AI endpoint lives: on this phone (loopback), on the local network, or in the cloud. */
object Endpoints {
    private fun parse(url: String): URI? {
        val t = url.trim()
        if (t.isEmpty()) return null
        return runCatching { URI(if ("://" in t) t else "http://$t") }.getOrNull()
    }

    fun secure(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.isHttps && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null
    }

    fun credentialOrigin(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        return "${u.scheme}://${u.host}:${u.port}"
    }

    fun sameOrigin(first: String, second: String): Boolean {
        val a = first.toHttpUrlOrNull() ?: return false
        val b = second.toHttpUrlOrNull() ?: return false
        return a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }

    fun host(url: String): String? = parse(url)?.host?.lowercase()

    /** True for a server running on this phone, e.g. llama-server in Termux. */
    fun isLoopback(url: String): Boolean {
        val h = host(url) ?: return false
        return h == "localhost" || h == "::1" || h == "[::1]" || (h.startsWith("127.") && ipv4(h) != null)
    }

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part } ?: return null
        }
    }

    /** True when the endpoint works without the internet: this phone or the local network. */
    fun isLocal(url: String): Boolean {
        if (isLoopback(url)) return true
        val h = host(url) ?: return false
        if (h.endsWith(".local")) return true
        val p = ipv4(h) ?: return false
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
