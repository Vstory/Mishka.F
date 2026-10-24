package top.yukonga.mishka.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges mihomo's standalone child process to Android's authoritative socket-owner API.
 *
 * A fork+exec core cannot call [ConnectivityManager.getConnectionOwnerUid] directly. CMFA solves
 * that with an in-process JNI callback; Mishka deliberately runs a separate process, so a tiny
 * loopback UDP bridge is used instead. Requests never leave loopback and carry only protocol plus
 * source/destination socket addresses. The response is the owning package name.
 */
class AndroidProcessResolver(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        ?: error("ConnectivityManager unavailable")
    // Stable across Service recreation so a surviving ROOT core reconnects after the app process
    // is killed. Different Android app UIDs get different ports.
    private val socket = bindSocket()
    private val running = AtomicBoolean(true)
    val address: String = "$LOOPBACK:${socket.localPort}"

    private val worker = Thread({ serve() }, "mishka-process-resolver").apply {
        isDaemon = true
        start()
    }

    private fun serve() {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (running.get()) {
            val request = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(request)
                val text = String(request.data, request.offset, request.length, Charsets.UTF_8)
                val packageName = resolve(text).orEmpty()
                val response = packageName.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(response, response.size, request.address, request.port))
            } catch (_: SocketException) {
                if (running.get()) Log.w(TAG, "process resolver socket closed unexpectedly")
                return
            } catch (e: Exception) {
                Log.d(TAG, "process resolver request failed: ${e.message}")
            }
        }
    }

    private fun resolve(request: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val fields = request.trim().split('\t')
        if (fields.size != 3) return null
        val protocol = when (fields[0]) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> return null
        }
        val source = parseSocketAddress(fields[1]) ?: return null
        val target = parseSocketAddress(fields[2]) ?: return null
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(protocol, source, target)
        }.getOrDefault(-1)
        if (uid < 0) return null
        val packages = appContext.packageManager.getPackagesForUid(uid).orEmpty()
        // sharedUserId is rare; prefer a non-Mishka package so the VPN's own control connection
        // cannot mask the actual app when Android reports a shared UID.
        return packages.firstOrNull { it != appContext.packageName } ?: packages.firstOrNull()
    }

    private fun parseSocketAddress(value: String): InetSocketAddress? {
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val end = value.lastIndexOf("]:" )
            if (end <= 1) return null
            host = value.substring(1, end)
            portText = value.substring(end + 2)
        } else {
            val split = value.lastIndexOf(':')
            if (split <= 0) return null
            host = value.substring(0, split)
            portText = value.substring(split + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
        return InetSocketAddress(InetAddress.getByName(host.substringBefore('%')), port)
    }

    private fun bindSocket(): DatagramSocket {
        val preferredPort = BASE_PORT + (Process.myUid() % PORT_SPAN)
        return runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), preferredPort))
            }
        }.getOrElse {
            Log.w(TAG, "stable resolver port $preferredPort unavailable; using ephemeral port")
            DatagramSocket(0, InetAddress.getByName(LOOPBACK))
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        socket.close()
        runCatching { worker.join(200) }
    }

    companion object {
        private const val TAG = "AndroidProcessResolver"
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_PACKET_SIZE = 1024
        private const val BASE_PORT = 32000
        private const val PORT_SPAN = 10000
    }
}
