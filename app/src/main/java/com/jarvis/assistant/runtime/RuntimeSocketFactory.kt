package com.jarvis.assistant.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** OkHttp uses HTTP/1.1 over the child's private filesystem socket. No TCP fallback. */
internal class RuntimeSocketFactory(private val ownedPath: () -> String?) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        private val local = LocalSocket()
        private var timeoutMs = 0
        @Volatile private var closed = false

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val path = ownedPath() ?: throw IOException("On-device model has stopped")
            local.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            if (local.peerCredentials.uid != Process.myUid() || ownedPath() != path) {
                close()
                throw IOException("On-device model identity changed")
            }
            local.soTimeout = timeoutMs
        }

        override fun getInputStream() = local.inputStream
        override fun getOutputStream() = local.outputStream
        override fun setSoTimeout(timeout: Int) {
            timeoutMs = timeout
            if (local.isConnected) local.soTimeout = timeout
        }
        override fun getSoTimeout() = timeoutMs
        override fun isConnected() = local.isConnected
        override fun isClosed() = closed
        override fun close() {
            closed = true
            local.close()
        }
    }

    private fun unsupported(): Nothing = throw IOException("Use the private runtime connection")
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = unsupported()
}
