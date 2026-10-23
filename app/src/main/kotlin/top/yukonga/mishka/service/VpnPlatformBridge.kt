package top.yukonga.mishka.service

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.UUID

internal fun resolveUidPackageName(uid: Int, packageInfos: Collection<PackageInfo>): String {
    val groups = packageInfos
        .asSequence()
        .filter { it.applicationInfo?.uid == uid && it.packageName.isNotBlank() }
        .groupBy { info ->
            info.sharedUserId?.takeIf { it.isNotBlank() } ?: info.packageName
        }

    if (groups.size != 1) return ""

    val group = groups.entries.single()
    return if (group.value.size == 1) {
        group.value.single().packageName
    } else {
        group.key
    }
}

class VpnPlatformBridge(
    private val vpn: VpnService,
    private val scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit,
) : AutoCloseable {
    val socketName: String = "mishka-vpn-${Process.myPid()}-${UUID.randomUUID()}"

    private val connectivity = requireNotNull(vpn.getSystemService(ConnectivityManager::class.java))
    private val lock = Any()
    private var closed = false
    private var client: LocalSocket? = null
    private var job: Job? = null
    private val server = LocalServerSocket(socketName)

    fun start() {
        val task = synchronized(lock) {
            check(!closed) { "VPN platform bridge is closed" }
            if (job != null) return
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                coroutineScope {
                    // 阻塞 socket 不响应协程取消，独立子任务在取消开始时关闭 fd，而非等父任务完成。
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            close()
                        }
                    }
                    try {
                        while (isActive) {
                            val accepted = try {
                                server.accept()
                            } catch (error: IOException) {
                                if (synchronized(lock) { closed }) break
                                throw error
                            }
                            val attached = synchronized(lock) {
                                if (closed) false else {
                                    client = accepted
                                    true
                                }
                            }
                            if (!attached) {
                                closeSocket(accepted)
                                break
                            }
                            try {
                                // ABSTRACT socket 没有文件权限，必须在读取任何请求前验证内核提供的 UID。
                                if (accepted.peerCredentials.uid == Process.myUid()) serve(accepted)
                            } catch (_: IOException) {
                                // 单个连接损坏不能终止 bridge；子进程退出也会走这里。
                            } finally {
                                synchronized(lock) {
                                    if (client === accepted) client = null
                                }
                                closeSocket(accepted)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (!synchronized(lock) { closed }) onFailure(error)
                    } finally {
                        cancellation.cancel()
                        close()
                    }
                }
            }.also { job = it }
        }
        task.invokeOnCompletion { close() }
        task.start()
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            client.also { client = null }
        }
        active?.let(::closeSocket)
        // 仅 close 监听 fd 不保证唤醒另一个线程的 accept，先 shutdown 解除内核等待。
        server.fileDescriptor?.let(::shutdown)
        closeQuietly(server)
        job?.cancel()
    }

    private fun serve(socket: LocalSocket) {
        val raw = socket.inputStream
        val input = DataInputStream(object : InputStream() {
            override fun read(): Int = raw.read().also { rejectDescriptors(socket) }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                raw.read(bytes, offset, length).also { rejectDescriptors(socket) }
        })
        val output = DataOutputStream(socket.outputStream)
        while (true) {
            val opcode = raw.read()
            val descriptors = socket.ancillaryFileDescriptors
            try {
                when (opcode) {
                    -1 -> return
                    1 -> {
                        val protected = descriptors?.singleOrNull()?.let { descriptor ->
                            // protect 的公开 API 只接受整数 fd；这个临时 dup 和收到的 SCM_RIGHTS fd 各自释放。
                            ParcelFileDescriptor.dup(descriptor).use { vpn.protect(it.fd) }
                        } ?: false
                        output.writeByte(if (protected) 1 else 0)
                    }

                    2 -> {
                        if (!descriptors.isNullOrEmpty()) throw IOException("Unexpected platform descriptors")
                        val protocol = input.readInt()
                        val source = input.readAddress()
                        val target = input.readAddress()
                        val uid = queryUid(protocol, source, target)
                        val name = resolvePackageName(uid)
                        val bytes = name.toByteArray(Charsets.UTF_8)
                        if (bytes.size > MAX_ADDRESS_BYTES) throw IOException("Package name is too long")
                        output.writeInt(uid)
                        output.writeInt(bytes.size)
                        output.write(bytes)
                    }

                    else -> throw IOException("Unknown VPN platform opcode: $opcode")
                }
                output.flush()
            } finally {
                descriptors?.forEach(::closeDescriptor)
            }
        }
    }

    private fun queryUid(protocol: Int, source: InetSocketAddress?, target: InetSocketAddress?): Int {
        if (source == null || target == null ||
            (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP)
        ) return -1
        return try {
            connectivity.getConnectionOwnerUid(protocol, source, target)
        } catch (_: SecurityException) {
            -1
        } catch (_: IllegalArgumentException) {
            -1
        }
    }

    private fun resolvePackageName(uid: Int): String {
        if (uid < 0) return ""

        val packageNames = try {
            vpn.packageManager.getPackagesForUid(uid).orEmpty()
        } catch (_: SecurityException) {
            return ""
        }

        val packageInfos = packageNames
            .asSequence()
            .distinct()
            .mapNotNull { packageName ->
                try {
                    vpn.packageManager.getPackageInfo(packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
            .toList()

        return resolveUidPackageName(uid, packageInfos)
    }

    private fun DataInputStream.readAddress(): InetSocketAddress? {
        val length = readInt()
        if (length !in 1..MAX_ADDRESS_BYTES) throw IOException("Invalid VPN platform address length")
        val bytes = ByteArray(length)
        readFully(bytes)
        val value = bytes.toString(Charsets.UTF_8)
        val bracketed = value.startsWith('[')
        val separator = if (bracketed) {
            value.indexOf("]:").takeIf { it >= 0 }?.plus(1) ?: -1
        } else value.indexOf(':')
        if (separator <= 0 || separator >= value.lastIndex) return null
        val host = if (bracketed) value.substring(1, separator - 1) else value.substring(0, separator)
        val portText = value.substring(separator + 1)
        if (portText.any { it !in '0'..'9' }) return null
        val port = portText.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
        val zone = host.substringAfter('%', "")
        if (zone.any { it !in '0'..'9' }) return null
        val scopeId = if ('%' in host) zone.toIntOrNull()?.takeIf { it >= 0 } ?: return null else 0
        val address = try {
            InetAddresses.parseNumericAddress(host.substringBefore('%'))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!bracketed && address is Inet6Address) return null
        if ('%' in host && address !is Inet6Address) return null
        val scoped = if (address is Inet6Address && scopeId != 0) {
            Inet6Address.getByAddress(null, address.address, scopeId)
        } else address
        return InetSocketAddress(scoped, port)
    }

    private fun rejectDescriptors(socket: LocalSocket) {
        val descriptors = socket.ancillaryFileDescriptors ?: return
        descriptors.forEach(::closeDescriptor)
        throw IOException("Unexpected VPN platform descriptors")
    }

    private fun closeSocket(socket: LocalSocket) {
        socket.fileDescriptor?.let(::shutdown)
        closeQuietly(socket)
    }

    private fun shutdown(descriptor: FileDescriptor) {
        try {
            Os.shutdown(descriptor, OsConstants.SHUT_RDWR)
        } catch (_: ErrnoException) {
        }
    }

    private fun closeDescriptor(descriptor: FileDescriptor) {
        try {
            Os.close(descriptor)
        } catch (_: ErrnoException) {
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val MAX_ADDRESS_BYTES = 1024
    }
}
