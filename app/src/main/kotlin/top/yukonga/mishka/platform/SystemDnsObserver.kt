package top.yukonga.mishka.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.net.Inet6Address

class SystemDnsObserver(context: Context) {
    private val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        .build()

    private class NetworkInfo(
        var capabilities: NetworkCapabilities? = null,
        var linkProperties: LinkProperties? = null,
        var losingUntil: Long = 0,
    )

    /** 启动快照包含平台同步查询，调用方必须在 IO 线程执行。 */
    fun snapshot(): List<String> = selectDns(readNetworks()) ?: emptyList()

    fun observe(): Flow<List<String>> = callbackFlow {
        val lock = Any()
        val networks = mutableMapOf<Network, NetworkInfo>()

        fun publish() {
            selectDns(networks)?.let { trySend(it) }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    networks.getOrPut(network) { NetworkInfo() }.losingUntil = 0
                    publish()
                }
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                synchronized(lock) {
                    networks[network]?.losingUntil = SystemClock.elapsedRealtime() + maxMsToLive
                    publish()
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    networks.remove(network)
                    publish()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                synchronized(lock) {
                    networks[network]?.capabilities = networkCapabilities
                    publish()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                synchronized(lock) {
                    networks[network]?.linkProperties = linkProperties
                    publish()
                }
            }
        }

        synchronized(lock) {
            connectivity.registerNetworkCallback(request, callback)
            try {
                // 先注册再取快照，堵住两者之间的事件空窗；排队回调随后按序覆盖快照。
                readNetworks(networks)
                trySend(selectDns(networks) ?: emptyList())
            } catch (error: Throwable) {
                connectivity.unregisterNetworkCallback(callback)
                throw error
            }
        }
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.buffer(Channel.CONFLATED)
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    // 回调只使用事件携带的对象；回调内同步查询可能读到过期或 null 状态。
    private fun readNetworks(
        networks: MutableMap<Network, NetworkInfo> = mutableMapOf(),
    ): Map<Network, NetworkInfo> {
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (isEligible(capabilities)) {
                networks[network] = NetworkInfo(capabilities, connectivity.getLinkProperties(network))
            }
        }
        return networks
    }

    private fun isEligible(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)

    private fun selectDns(networks: Map<Network, NetworkInfo>): List<String>? {
        val now = SystemClock.elapsedRealtime()
        var selected: NetworkInfo? = null
        var selectedPriority = Int.MAX_VALUE
        var selectedHandle = Long.MAX_VALUE
        var pendingCapabilities = false
        for ((network, info) in networks) {
            val capabilities = info.capabilities
            if (capabilities == null) {
                pendingCapabilities = true
                continue
            }
            if (!isEligible(capabilities)) continue
            // 切换窗口内降低即将丢失网络的优先级，优先交给替代网络。
            val priority = transportPriority(capabilities) + if (now < info.losingUntil) 10 else 0
            val handle = network.networkHandle
            if (priority < selectedPriority || (priority == selectedPriority && handle < selectedHandle)) {
                selected = info
                selectedPriority = priority
                selectedHandle = handle
            }
        }
        if (selected == null) return if (pendingCapabilities) null else emptyList()
        // onAvailable 后会依次补齐这两个字段，期间不能把尚未就绪误报成断网。
        val properties = selected.linkProperties ?: return null
        return properties.dnsServers.mapNotNull { address ->
            val host = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
            if (address is Inet6Address) {
                // 接口名 zone 依赖解析进程的接口视图；跨进程传数字 scope 保留链路本地 DNS 路由。
                val scope = if (address.scopeId > 0) "%${address.scopeId}" else ""
                "[$host$scope]:53"
            } else "$host:53"
        }
    }

    private fun transportPriority(capabilities: NetworkCapabilities): Int = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 5

        else -> 20
    }
}
