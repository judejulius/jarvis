package com.jarvis.assistant.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class GgufDownloaderTest {
    private val body: ByteArray = ByteArray(700_000).also { java.util.Random(1).nextBytes(it); "GGUF".toByteArray().copyInto(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A tiny HTTP server: HEAD/GET, Range, optional redirect, optional connection cut, custom checksum header. */
    private class Server(val data: ByteArray, val linkedSha: String?, var cutFirstGetAfter: Long = -1, val redirect: Boolean = false) {
        val ss = ServerSocket(0)
        val requests = CopyOnWriteArrayList<String>()
        private var gets = 0
        val base get() = "http://127.0.0.1:${ss.localPort}"

        init {
            thread(isDaemon = true) {
                while (!ss.isClosed) {
                    try { ss.accept().use { handle(it) } } catch (_: Exception) { }
                }
            }
        }

        private fun handle(s: java.net.Socket) {
            val r = s.getInputStream().bufferedReader()
            val line = r.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
            val (method, path) = line.split(' ').let { it[0] to it[1] }
            requests += "$method $path ${headers["range"].orEmpty()}".trim()
            val out = s.getOutputStream()
            if (redirect && path == "/model.gguf") {
                out.write("HTTP/1.1 302 Found\r\nLocation: /cdn/model.gguf\r\nContent-Length: 0\r\nConnection: close\r\n".toByteArray())
                if (linkedSha != null) out.write("X-Linked-Size: ${data.size}\r\nX-Linked-ETag: \"$linkedSha\"\r\n".toByteArray())
                out.write("\r\n".toByteArray()); out.flush(); return
            }
            val from = headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            val slice = data.copyOfRange(from.toInt(), data.size)
            val status = if (from > 0) "206 Partial Content" else "200 OK"
            val sb = StringBuilder("HTTP/1.1 $status\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n")
            if (from > 0) sb.append("Content-Range: bytes $from-${data.size - 1}/${data.size}\r\n")
            if (linkedSha != null && !redirect) sb.append("X-Linked-Size: ${data.size}\r\nX-Linked-ETag: \"$linkedSha\"\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
            if (method == "HEAD") { out.flush(); return }
            gets++
            if (gets == 1 && cutFirstGetAfter >= 0) { out.write(slice, 0, cutFirstGetAfter.toInt()); out.flush(); return }
            out.write(slice); out.flush()
        }

        fun close() = ss.close()
    }

    private fun tmp(): File = File.createTempFile("gguf", "dir").let { it.delete(); it.mkdirs(); it }

    @Test fun downloadsVerifiesAndNeverDownloadsTwice() {
        val srv = Server(body, sha(body)); val dir = tmp()
        try {
            val d = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), retryPauseMs = 10)
            val progress = ArrayList<Long>()
            val r = d.download({ done, _ -> progress += done })
            assertTrue(r.toString(), r is GgufDownloader.Result.Ok && !(r as GgufDownloader.Result.Ok).alreadyPresent)
            assertEquals(sha(body), GgufDownloader.sha256Of(File(dir, "m.gguf")))
            assertTrue(progress.isNotEmpty() && progress.last() == body.size.toLong())
            val gets = srv.requests.count { it.startsWith("GET") }
            val again = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf")).download()
            assertTrue(again is GgufDownloader.Result.Ok && (again as GgufDownloader.Result.Ok).alreadyPresent)
            assertEquals(gets, srv.requests.count { it.startsWith("GET") })
        } finally { srv.close() }
    }

    @Test fun resumesAfterTheConnectionDrops() {
        val srv = Server(body, sha(body), cutFirstGetAfter = 250_000); val dir = tmp()
        try {
            val r = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), retryPauseMs = 10).download()
            assertTrue(r.toString(), r is GgufDownloader.Result.Ok)
            assertEquals(sha(body), GgufDownloader.sha256Of(File(dir, "m.gguf")))
            val ranged = srv.requests.filter { it.startsWith("GET") && "bytes=" in it }
            assertTrue("expected a resumed Range request, got ${srv.requests}", ranged.isNotEmpty())
            assertTrue(ranged.first().substringAfter("bytes=").substringBefore('-').toLong() in 1..body.size.toLong())
        } finally { srv.close() }
    }

    @Test fun aCorruptFileIsRejectedAndRemoved() {
        val srv = Server(body, sha("different".toByteArray())); val dir = tmp()
        try {
            val r = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), retryPauseMs = 10).download()
            assertTrue(r.toString(), r is GgufDownloader.Result.Failed && (r as GgufDownloader.Result.Failed).retryable)
            assertFalse(File(dir, "m.gguf").exists())
            assertFalse(File(dir, "m.gguf.part").exists())
        } finally { srv.close() }
    }

    @Test fun notEnoughStorageStopsBeforeDownloading() {
        val srv = Server(body, sha(body)); val dir = tmp()
        try {
            val r = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), freeSpace = { 10_000L }).download()
            assertTrue(r.toString(), r is GgufDownloader.Result.Failed && !(r as GgufDownloader.Result.Failed).retryable)
            assertTrue((r as GgufDownloader.Result.Failed).reason.contains("storage"))
            assertEquals(0, srv.requests.count { it.startsWith("GET") })
        } finally { srv.close() }
    }

    @Test fun aFileThatIsNotAModelIsRejected() {
        val junk = ByteArray(300_000) { 7 }
        val srv = Server(junk, sha(junk)); val dir = tmp()
        try {
            val r = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), retryPauseMs = 10).download()
            assertTrue(r.toString(), r is GgufDownloader.Result.Failed)
            assertFalse(File(dir, "m.gguf").exists())
        } finally { srv.close() }
    }

    @Test fun followsRedirectsAndReadsTheChecksumFromTheFirstResponse() {
        val srv = Server(body, sha(body), redirect = true); val dir = tmp()
        try {
            val r = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), retryPauseMs = 10).download()
            assertTrue(r.toString(), r is GgufDownloader.Result.Ok)
            assertTrue(srv.requests.any { it.contains("/cdn/model.gguf") })
        } finally { srv.close() }
    }

    @Test fun cancelKeepsThePartialFileForLater() {
        val srv = Server(body, sha(body)); val dir = tmp()
        try {
            val d = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), retryPauseMs = 10)
            val r = d.download({ done, _ -> if (done > 100_000) d.cancel() })
            assertEquals(GgufDownloader.Result.Cancelled, r)
            assertTrue(File(dir, "m.gguf.part").length() > 0)
            assertFalse(File(dir, "m.gguf").exists())
            val done = GgufDownloader("${srv.base}/model.gguf", File(dir, "m.gguf"), retryPauseMs = 10).download()
            assertTrue(done.toString(), done is GgufDownloader.Result.Ok)
            assertEquals(sha(body), GgufDownloader.sha256Of(File(dir, "m.gguf")))
        } finally { srv.close() }
    }

    @Test fun plainHttpToARemoteHostIsRefused() {
        val r = GgufDownloader("http://example.com/model.gguf", File(tmp(), "m.gguf"), retryPauseMs = 10).download()
        assertTrue(r.toString(), r is GgufDownloader.Result.Failed)
    }
}
