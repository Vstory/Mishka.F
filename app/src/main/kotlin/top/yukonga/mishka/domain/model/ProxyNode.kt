package top.yukonga.mishka.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProxyNode(
    val name: String = "",
    val type: String = "",
    val alive: Boolean = true,
    val now: String = "",
    val all: List<String> = emptyList(),
    val history: List<ProxyHistory> = emptyList(),
    val udp: Boolean = false,
    val xudp: Boolean = false,
    val tfo: Boolean = false,
    val icon: String = "",
    val fixed: String = "",
    val hidden: Boolean = false,
    val testUrl: String = "",
    val expectedStatus: String = "",
    val uot: Boolean = false,
    val mptcp: Boolean = false,
    val smux: Boolean = false,
    @SerialName("interface") val networkInterface: String = "",
    @SerialName("dialer-proxy") val dialerProxy: String = "",
    @SerialName("routing-mark") val routingMark: Int = 0,
    @SerialName("provider-name") val providerName: String = "",
)

@Serializable
data class ProxyHistory(
    val time: String = "",
    val delay: Int = 0,
)
