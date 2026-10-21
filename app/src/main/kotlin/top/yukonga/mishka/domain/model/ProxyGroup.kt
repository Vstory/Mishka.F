package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ProxiesResponse(
    val proxies: Map<String, ProxyNode> = emptyMap(),
)

@Serializable
data class GroupsResponse(
    val proxies: List<ProxyNode> = emptyList(),
)

@Serializable
data class ProxyGroupDetail(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: List<ProxyNode> = emptyList(),
    val history: List<ProxyHistory> = emptyList(),
    val hidden: Boolean = false,
    val testUrl: String = "",
    val expectedStatus: String = "",
    val icon: String = "",
    val fixed: String = "",
)
