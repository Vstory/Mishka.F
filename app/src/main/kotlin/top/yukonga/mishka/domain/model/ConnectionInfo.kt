package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionsResponse(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<ConnectionInfo> = emptyList(),
    val memory: Long = 0,
)

@Serializable
data class ConnectionInfo(
    val id: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val providerChains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
    val metadata: ConnectionMetadata = ConnectionMetadata(),
)

@Serializable
data class ConnectionMetadata(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
    val dnsMode: String = "",
    val processPath: String = "",
    val process: String = "",
    val sniffHost: String = "",
    val destinationGeoIP: List<String> = emptyList(),
    val sourceGeoIP: List<String> = emptyList(),
    val destinationIPASN: String = "",
    val sourceIPASN: String = "",
    val inboundIP: String = "",
    val inboundPort: String = "",
    val inboundName: String = "",
    val inboundUser: String = "",
    val uid: Int = 0,
    val remoteDestination: String = "",
    val specialProxy: String = "",
    val specialRules: String = "",
    val dscp: Int = 0,
)
