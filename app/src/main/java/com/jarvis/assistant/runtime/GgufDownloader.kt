package com.jarvis.assistant.runtime

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads one large model file (a GGUF) safely: checks free storage first, resumes an interrupted download from
 * where it stopped, follows redirects only over HTTPS, and verifies the finished file (size, GGUF header and, when the
 * server publishes it, the SHA-256) before it is put in place. A file that is already complete is never downloaded again.
 * No Android classes, so it is unit-tested on the JVM. Call [download] from a background thread.
 */
class GgufDownloader(
    private val url: String,
    private val dest: File,
    /** Overrides what the server reports. */
    private val expectedSha256: String? = null,
    private val expectedSize: Long? = null,
    private val freeSpace: () -> Long = { (dest.absoluteFile.parentFile ?: File(".")).usableSpace },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxAttempts: Int = 5,
    private val retryPauseMs: Long = 1500L,
) {
    sealed interface Result {
        data class Ok(val file: File, val alreadyPresent: Boolean) : Result
        data class Failed(val reason: String, val retryable: Boolean) : Result
        data object Cancelled : Result
    }

    private class Remote(val size: Long?, val sha256: String?)

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    private val part = File(dest.path + ".part")
    private val marker = File(dest.path + ".ok")

    /** True when [dest] was fully downloaded and verified earlier (and nothing has changed it since). */
    fun isComplete(): Boolean =
        dest.isFile && marker.isFile && marker.readText().trim() == "${dest.length()}" &&
            (expectedSize == null || dest.length() == expectedSize) && hasGgufHeader(dest)

    fun download(onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }, onPhase: (String) -> Unit = {}): Result {
        cancelled = false
        if (isComplete()) return Result.Ok(dest, alreadyPresent = true)
        dest.absoluteFile.parentFile?.mkdirs()

        onPhase("Checking the download")
        val remote = try { fetchRemote() } catch (e: IOException) { return Result.Failed("Can't reach the download server: ${e.message}", true) }
        val total = expectedSize ?: remote.size ?: return Result.Failed("The server did not say how big the file is.", true)
        val sha = (expectedSha256 ?: remote.sha256)?.lowercase()

        val have = if (part.isFile) part.length() else 0L
        if (have > total) part.delete()
        val needed = total - (if (part.isFile) part.length() else 0L) + SPACE_MARGIN
        if (freeSpace() < needed) {
            return Result.Failed("Not enough storage: about ${needed / 1_000_000} MB free space is needed.", false)
        }

        var attempt = 0
        var lastError = "unknown error"
        while (attempt < maxAttempts) {
            if (cancelled) return Result.Cancelled
            attempt++
            onPhase("Downloading")
            try {
                if (!fetchInto(part, total, onProgress)) return Result.Cancelled
                break
            } catch (e: IOException) {
                lastError = e.message ?: e.javaClass.simpleName
                if (attempt >= maxAttempts) return Result.Failed("The download was interrupted ($lastError). It will resume from where it stopped.", true)
                onPhase("Connection lost, retrying")
                if (!pause(retryPauseMs * attempt)) return Result.Cancelled
            }
        }

        onPhase("Verifying")
        onProgress(total, total)
        if (part.length() != total) {
            part.delete()
            return Result.Failed("The downloaded file has the wrong size.", true)
        }
        if (!hasGgufHeader(part)) {
            part.delete()
            return Result.Failed("The downloaded file is not a model file.", true)
        }
        if (sha != null && sha256Of(part) != sha) {
            part.delete()
            return Result.Failed("The downloaded file is corrupted (checksum mismatch). Please try again.", true)
        }
        if (dest.exists() && !dest.delete()) return Result.Failed("Could not replace the old model file.", false)
        if (!part.renameTo(dest)) return Result.Failed("Could not save the model file.", false)
        marker.writeText("${dest.length()}")
        return Result.Ok(dest, alreadyPresent = false)
    }

    // ---- network ----

    private fun open(target: String, method: String, rangeFrom: Long?): HttpURLConnection {
        var current = target
        repeat(MAX_REDIRECTS) {
            val u = URL(current)
            val local = u.host == "localhost" || u.host == "127.0.0.1"
            if (u.protocol != "https" && !local) throw IOException("Refusing a non-HTTPS download address")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.requestMethod = method
            c.setRequestProperty("User-Agent", "JarvisAssistant")
            if (rangeFrom != null && rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
            val code = c.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val next = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                c.disconnect()
                current = URI(current).resolve(next).toString()
                return@repeat
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    /** HEAD without following the first redirect: Hugging Face puts the file's size and SHA-256 on that response. */
    private fun fetchRemote(): Remote {
        var size: Long? = null
        var sha: String? = null
        val first = URL(url).openConnection() as HttpURLConnection
        try {
            val u = URL(url)
            if (u.protocol != "https" && u.host != "localhost" && u.host != "127.0.0.1") throw IOException("Refusing a non-HTTPS download address")
            first.instanceFollowRedirects = false
            first.connectTimeout = connectTimeoutMs
            first.readTimeout = readTimeoutMs
            first.requestMethod = "HEAD"
            first.setRequestProperty("User-Agent", "JarvisAssistant")
            first.responseCode
            size = first.getHeaderField("X-Linked-Size")?.toLongOrNull()
            sha = first.getHeaderField("X-Linked-ETag")?.trim('"', ' ')?.takeIf { it.length == 64 }
        } finally {
            first.disconnect()
        }
        if (size == null) {
            val c = open(url, "HEAD", null)
            try {
                if (c.responseCode !in 200..299) throw IOException("The server answered ${c.responseCode}")
                size = c.getHeaderField("Content-Length")?.toLongOrNull()
                if (sha == null) sha = c.getHeaderField("X-Linked-ETag")?.trim('"', ' ')?.takeIf { it.length == 64 }
            } finally {
                c.disconnect()
            }
        }
        return Remote(size, sha)
    }

    /** Returns false if cancelled. Throws IOException when the connection drops. */
    private fun fetchInto(target: File, total: Long, onProgress: (Long, Long) -> Unit): Boolean {
        var done = if (target.isFile) target.length() else 0L
        if (done == total) return true
        val c = open(url, "GET", done)
        try {
            val code = c.responseCode
            if (code == 200 && done > 0) { target.delete(); done = 0 } // server ignored the Range header: start over
            else if (code != 200 && code != 206) throw IOException("The server answered $code")
            if (code == 206) {
                val start = c.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (start != null && start != done) throw IOException("The server resumed from the wrong place")
            }
            RandomAccessFile(target, "rw").use { out ->
                out.seek(done)
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024 || done == total) { lastReport = done; onProgress(done, total) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (done < total) throw IOException("Connection closed early")
        return true
    }

    private fun pause(ms: Long): Boolean {
        var waited = 0L
        while (waited < ms) {
            if (cancelled) return false
            Thread.sleep(50)
            waited += 50
        }
        return true
    }

    companion object {
        private const val MAX_REDIRECTS = 6
        private const val SPACE_MARGIN = 64L * 1024 * 1024

        fun hasGgufHeader(f: File): Boolean = try {
            f.inputStream().use { s -> val h = ByteArray(4); s.read(h) == 4 && String(h, Charsets.US_ASCII) == "GGUF" }
        } catch (_: IOException) {
            false
        }

        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
