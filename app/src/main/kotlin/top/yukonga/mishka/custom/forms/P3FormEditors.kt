package top.yukonga.mishka.custom.forms

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.custom.runtime.TproxyAppFilter
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** DNS 服务器列表：逐条保留原串，点开后可视化拆分或切到原文编辑。 */
@Composable
internal fun DnsServerListDialog(
    title: String,
    ipOnly: Boolean,
    current: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    note: String? = null,
) {
    val servers = remember { mutableStateListOf<String>().also { it.addAll(current) } }
    var editing by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()
    val dragSort = rememberDragSortState(servers.size) { from, to ->
        val moved = servers.removeAt(from)
        servers.add(to, moved)
    }
    if (editing != null) {
        val index = editing!!
        DnsServerBuilderDialog(
            title = if (index < servers.size) "编辑 DNS 服务器" else "添加 DNS 服务器",
            initial = servers.getOrNull(index),
            ipOnly = ipOnly,
            onDismiss = { editing = null },
            onConfirm = { value ->
                if (servers.indices.contains(index)) {
                    if (servers.indices.any { it != index && servers[it] == value }) {
                        showToast("该服务器已在列表中")
                    } else {
                        servers[index] = value
                        editing = null
                    }
                } else if (servers.contains(value)) {
                    showToast("该服务器已在列表中")
                } else {
                    servers.add(value)
                    editing = null
                }
            },
        )
        return
    }

    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (servers.isEmpty()) DialogNote("暂无服务器，点击下方添加。打开已有配置只读解析，不会自动重写原文。")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .then(dragSort.containerModifier { scrollState.dispatchRawDelta(it) })
                    .verticalScroll(scrollState, enabled = dragSort.dragging < 0),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 顺序由 DragSortState.order 定（拖动中实时换位）；行内容与回调按原始下标取
                dragSort.order.forEach { index ->
                    if (index !in servers.indices) return@forEach
                    val value = servers[index]
                    DragSortRow(state = dragSort, index = index) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DragHandle(dragSort, index)
                            BasicComponent(
                                title = value,
                                summary = dnsServerSummary(value),
                                modifier = Modifier.weight(1f),
                                endActions = {
                                    // 与 mihomo_box 面板一致：行尾 ✎ 编辑 / × 删除，排序改拖把手
                                    MiniIconButton(MiuixIcons.Edit, "编辑") { editing = index }
                                    Spacer(Modifier.width(8.dp))
                                    MiniIconButton(MiuixIcons.Delete, "删除") { servers.removeAt(index) }
                                },
                            )
                        }
                    }
                }
            }
            note?.let { DialogNote(it) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "添加服务器", onClick = { editing = servers.size })
            }
            DialogButtons(onDismiss, onConfirm = {
                val normalized = servers.map { it.trim() }.filter { it.isNotEmpty() }
                onConfirm(normalized)
            })
        }
    }
}

private data class DnsServerParts(
    val protocol: String,
    val address: String,
    val port: String,
    val path: String,
    val rcode: String,
    val proxy: String,
    val h3: Boolean,
    val skipCert: Boolean,
    val nameCert: String,
    val ecs: String,
    val ecsOverride: Boolean,
    val disableIpv4: Boolean,
    val disableIpv6: Boolean,
    val extras: List<String>,
    val raw: String? = null,
)

private val DNS_PROTOCOLS = listOf(
    FormOption("udp", "UDP（明文）"),
    FormOption("tcp", "TCP"),
    FormOption("tls", "DoT"),
    FormOption("https", "DoH"),
    FormOption("quic", "DoQ"),
    FormOption("dhcp", "DHCP"),
    FormOption("system", "系统 DNS"),
    FormOption("rcode", "固定回应"),
)
private val DNS_PRESETS = listOf(
    FormOption("https://dns.alidns.com/dns-query", "阿里 DoH"),
    FormOption("tls://223.5.5.5", "阿里 DoT"),
    FormOption("223.5.5.5", "阿里 DNS"),
    FormOption("https://doh.pub/dns-query", "腾讯 DoH"),
    FormOption("119.29.29.29", "腾讯 DNSPod"),
    FormOption("114.114.114.114", "114 DNS"),
    FormOption("https://dns.google/dns-query", "Google DoH"),
    FormOption("8.8.8.8", "Google DNS"),
    FormOption("https://cloudflare-dns.com/dns-query", "Cloudflare DoH"),
    FormOption("1.1.1.1", "Cloudflare DNS"),
    FormOption("https://dns.quad9.net/dns-query", "Quad9 DoH"),
    FormOption("quic://dns.adguard.com", "AdGuard DoQ"),
    FormOption("system", "系统 DNS"),
)
private val DNS_RCODE_OPTIONS = listOf(
    FormOption("refused", "refused（拒绝查询）"),
    FormOption("name_error", "name_error（域名不存在）"),
    FormOption("success", "success（空应答）"),
    FormOption("server_failure", "server_failure（服务器失败）"),
    FormOption("format_error", "format_error（格式错误）"),
    FormOption("not_implemented", "not_implemented（未实现）"),
)
private val DNS_NETWORK_PROTOCOLS = setOf("udp", "tcp", "tls", "https", "quic")

private fun parseDnsServer(value: String?): DnsServerParts {
    val rawText = value.orEmpty().trim()
    val empty = DnsServerParts(
        protocol = "https", address = "", port = "", path = "/dns-query", rcode = "refused",
        proxy = "", h3 = false, skipCert = false, nameCert = "", ecs = "", ecsOverride = false,
        disableIpv4 = false, disableIpv6 = false, extras = emptyList(),
    )
    if (rawText.isEmpty()) return empty

    val hash = rawText.indexOf('#')
    val base = if (hash < 0) rawText else rawText.substring(0, hash)
    val proxy = StringBuilder()
    val extras = mutableListOf<String>()
    var h3 = false
    var skipCert = false
    var nameCert = ""
    var ecs = ""
    var ecsOverride = false
    var disableIpv4 = false
    var disableIpv6 = false
    if (hash >= 0) {
        for (token in rawText.substring(hash + 1).split('&').filter { it.isNotBlank() }) {
            val at = token.indexOf('=')
            val key = if (at < 0) token else token.substring(0, at)
            val arg = if (at < 0) "" else token.substring(at + 1)
            when {
                at < 0 && proxy.isEmpty() -> proxy.append(token)
                key == "h3" -> h3 = arg.equals("true", ignoreCase = true)
                key == "skip-cert-verify" -> skipCert = arg.equals("true", ignoreCase = true)
                key == "name-cert-verify" -> nameCert = arg
                key == "ecs" -> ecs = arg
                key == "ecs-override" -> ecsOverride = arg.equals("true", ignoreCase = true)
                key == "disable-ipv4" -> disableIpv4 = arg.equals("true", ignoreCase = true)
                key == "disable-ipv6" -> disableIpv6 = arg.equals("true", ignoreCase = true)
                else -> extras += token
            }
        }
    }

    var protocol = "udp"
    var address = ""
    var port = ""
    var path = "/dns-query"
    var rcode = "refused"
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.*)$").matchEntire(base)
    when {
        base == "system" || base == "system://" -> protocol = "system"
        scheme?.groupValues?.get(1) == "dhcp" -> {
            protocol = "dhcp"
            address = scheme!!.groupValues[2]
        }
        scheme?.groupValues?.get(1) == "rcode" -> {
            protocol = "rcode"
            rcode = scheme!!.groupValues[2].ifBlank { "refused" }
        }
        scheme != null && scheme.groupValues[1] in DNS_NETWORK_PROTOCOLS -> {
            protocol = scheme.groupValues[1]
            var hostAndPath = scheme.groupValues[2]
            if (protocol == "https") {
                val slash = hostAndPath.indexOf('/')
                if (slash >= 0) {
                    path = hostAndPath.substring(slash).ifBlank { "/dns-query" }
                    hostAndPath = hostAndPath.substring(0, slash)
                }
            }
            val parsed = splitDnsHostPort(hostAndPath)
            address = parsed.first
            port = parsed.second
        }
        scheme == null -> {
            val parsed = splitDnsHostPort(base)
            address = parsed.first
            port = parsed.second
        }
        else -> return empty.copy(raw = rawText)
    }
    if (protocol in DNS_NETWORK_PROTOCOLS && address.isBlank()) return empty.copy(raw = rawText)
    return DnsServerParts(
        protocol = protocol, address = address, port = port, path = path, rcode = rcode,
        proxy = proxy.toString(), h3 = h3, skipCert = skipCert, nameCert = nameCert, ecs = ecs,
        ecsOverride = ecsOverride, disableIpv4 = disableIpv4, disableIpv6 = disableIpv6, extras = extras,
    )
}

private fun splitDnsHostPort(value: String): Pair<String, String> {
    val text = value.trim()
    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close > 0) {
            val host = text.substring(1, close)
            val suffix = text.substring(close + 1)
            return host to if (suffix.startsWith(":") && suffix.drop(1).all(Char::isDigit)) suffix.drop(1) else ""
        }
    }
    val first = text.indexOf(':')
    val last = text.lastIndexOf(':')
    if (first > 0 && first == last && text.substring(last + 1).all(Char::isDigit)) {
        return text.substring(0, last) to text.substring(last + 1)
    }
    return text to ""
}

private fun isIpLiteral(value: String): Boolean {
    val host = value.trim().removePrefix("[").removeSuffix("]")
    val ipv4 = host.split('.')
    if (ipv4.size == 4 && ipv4.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) return true
    return host.contains(':') && host.matches(Regex("^[0-9a-fA-F:]+$"))
}

private fun buildDnsServer(parts: DnsServerParts): String {
    val p = parts.protocol
    val address = parts.address.trim()
    val port = parts.port.trim()
    val extras = mutableListOf<String>()
    if (parts.proxy.isNotBlank() && p in DNS_NETWORK_PROTOCOLS) extras += parts.proxy.trim()
    if (p == "https" && parts.h3) extras += "h3=true"
    if (p in setOf("tls", "https", "quic") && parts.skipCert) extras += "skip-cert-verify=true"
    if (p in setOf("tls", "https", "quic") && parts.nameCert.isNotBlank()) extras += "name-cert-verify=${parts.nameCert.trim()}"
    if (parts.ecs.isNotBlank() && p in DNS_NETWORK_PROTOCOLS) {
        extras += "ecs=${parts.ecs.trim()}"
        if (parts.ecsOverride) extras += "ecs-override=true"
    }
    if (parts.disableIpv4 && p in DNS_NETWORK_PROTOCOLS) extras += "disable-ipv4=true"
    if (parts.disableIpv6 && p in DNS_NETWORK_PROTOCOLS) extras += "disable-ipv6=true"
    extras += parts.extras
    val hostPort = (if (address.contains(':')) "[$address]" else address) + if (port.isBlank()) "" else ":$port"
    val base = when (p) {
        "system" -> "system"
        "dhcp" -> "dhcp://${address.ifBlank { "system" }}"
        "rcode" -> "rcode://${parts.rcode.ifBlank { "refused" }}"
        "udp" -> if (port.isBlank()) address else "udp://$hostPort"
        "https" -> "https://$hostPort${parts.path.trim().let { if (it.isBlank()) "/dns-query" else if (it.startsWith('/')) it else "/$it" }}"
        else -> "$p://$hostPort"
    }
    return if (extras.isEmpty()) base else "$base#${extras.joinToString("&")}"
}

private fun dnsServerSummary(value: String): String {
    val parsed = parseDnsServer(value)
    return if (parsed.raw != null) "无法拆分的服务器串 · 原样保留" else when (parsed.protocol) {
        "https" -> "DoH${parsed.path.takeIf { it != "/dns-query" }?.let { " · $it" }.orEmpty()}"
        "tls" -> "DoT"
        "quic" -> "DoQ"
        "dhcp" -> "DHCP"
        "system" -> "系统 DNS"
        "rcode" -> "固定回应 · ${parsed.rcode}"
        else -> parsed.protocol.uppercase()
    }
}

@Composable
private fun DnsServerBuilderDialog(
    title: String,
    initial: String?,
    ipOnly: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parsed = remember(initial, ipOnly) { parseDnsServer(initial) }
    var protocol by remember(initial, ipOnly) { mutableStateOf(parsed.protocol) }
    var address by remember(initial, ipOnly) { mutableStateOf(parsed.address) }
    var port by remember(initial, ipOnly) { mutableStateOf(parsed.port) }
    var path by remember(initial, ipOnly) { mutableStateOf(parsed.path) }
    var rcode by remember(initial, ipOnly) { mutableStateOf(parsed.rcode) }
    var proxy by remember(initial, ipOnly) { mutableStateOf(parsed.proxy) }
    var h3 by remember(initial, ipOnly) { mutableStateOf(parsed.h3) }
    var skipCert by remember(initial, ipOnly) { mutableStateOf(parsed.skipCert) }
    var nameCert by remember(initial, ipOnly) { mutableStateOf(parsed.nameCert) }
    var ecs by remember(initial, ipOnly) { mutableStateOf(parsed.ecs) }
    var ecsOverride by remember(initial, ipOnly) { mutableStateOf(parsed.ecsOverride) }
    var disableIpv4 by remember(initial, ipOnly) { mutableStateOf(parsed.disableIpv4) }
    var disableIpv6 by remember(initial, ipOnly) { mutableStateOf(parsed.disableIpv6) }
    var raw by remember(initial, ipOnly) { mutableStateOf(parsed.raw ?: initial.orEmpty()) }
    var rawMode by remember(initial, ipOnly) { mutableStateOf(parsed.raw != null) }
    var picker by remember(initial, ipOnly) { mutableStateOf<String?>(null) }
    val protocols = remember(ipOnly) { DNS_PROTOCOLS.filter { !ipOnly || it.value in DNS_NETWORK_PROTOCOLS } }
    val preview = buildDnsServer(
        DnsServerParts(protocol, address, port, path, rcode, proxy, h3, skipCert, nameCert, ecs, ecsOverride, disableIpv4, disableIpv6, parsed.extras),
    )

    if (picker == "protocol") {
        OptionPickerDialog(
            title = "DNS 协议",
            options = protocols,
            current = protocol,
            onDismiss = { picker = null },
            onPick = { protocol = it; picker = null },
        )
        return
    }
    if (picker == "preset") {
        val presets = remember(ipOnly) { DNS_PRESETS.filter { !ipOnly || isIpLiteral(parseDnsServer(it.value).address) } }
        OptionPickerDialog(
            title = "选择 DNS 预设",
            options = presets,
            current = null,
            onDismiss = { picker = null },
            onPick = { value ->
                val next = parseDnsServer(value)
                protocol = next.protocol
                address = next.address
                port = next.port
                path = next.path
                rcode = next.rcode
                proxy = next.proxy
                h3 = next.h3
                skipCert = next.skipCert
                nameCert = next.nameCert
                ecs = next.ecs
                ecsOverride = next.ecsOverride
                disableIpv4 = next.disableIpv4
                disableIpv6 = next.disableIpv6
                raw = ""
                rawMode = false
                picker = null
            },
        )
        return
    }
    if (picker == "rcode") {
        OptionPickerDialog(
            title = "固定回应类型",
            options = DNS_RCODE_OPTIONS,
            current = rcode,
            onDismiss = { picker = null },
            onPick = { rcode = it; picker = null },
        )
        return
    }

    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (rawMode) {
                TextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "完整服务器串",
                    singleLine = true,
                )
                DialogNote("无法识别的旧格式会以原串显示；修改时请保持 mihomo 的 DNS 服务器格式。")
                TextButton(text = "尝试切换到可视化", onClick = {
                    val next = parseDnsServer(raw)
                    if (next.raw != null) showToast("该串无法安全拆分，继续使用原文编辑", long = true)
                    else {
                        protocol = next.protocol
                        address = next.address
                        port = next.port
                        path = next.path
                        rcode = next.rcode
                        proxy = next.proxy
                        h3 = next.h3
                        skipCert = next.skipCert
                        nameCert = next.nameCert
                        ecs = next.ecs
                        ecsOverride = next.ecsOverride
                        disableIpv4 = next.disableIpv4
                        disableIpv6 = next.disableIpv6
                        rawMode = false
                    }
                })
            } else {
                BasicComponent(
                    title = "协议",
                    summary = protocols.firstOrNull { it.value == protocol }?.label ?: protocol,
                    onClick = { picker = "protocol" },
                )
                if (protocol in DNS_NETWORK_PROTOCOLS) {
                    TextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = if (ipOnly) "服务器 IP" else "服务器地址", singleLine = true)
                    TextField(port, { port = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = "端口（留空用默认）", singleLine = true)
                    if (protocol == "https") {
                        TextField(path, { path = it }, modifier = Modifier.fillMaxWidth(), label = "DoH 路径", singleLine = true)
                    }
                    if (!ipOnly) {
                        TextField(proxy, { proxy = it }, modifier = Modifier.fillMaxWidth(), label = "代理 / 网卡 / RULES（可选）", singleLine = true)
                    }
                    if (protocol in setOf("tls", "https", "quic")) {
                        DnsSwitchRow("跳过证书校验", skipCert) { skipCert = it }
                        TextField(nameCert, { nameCert = it }, modifier = Modifier.fillMaxWidth(), label = "证书 DNSName（可选）", singleLine = true)
                    }
                    if (protocol == "https") DnsSwitchRow("强制 HTTP/3", h3) { h3 = it }
                    TextField(ecs, { ecs = it }, modifier = Modifier.fillMaxWidth(), label = "ECS 子网（可选）", singleLine = true)
                    if (ecs.isNotBlank()) DnsSwitchRow("强制覆盖 ECS", ecsOverride) { ecsOverride = it }
                    DnsSwitchRow("丢弃 A 回应", disableIpv4) { disableIpv4 = it }
                    DnsSwitchRow("丢弃 AAAA 回应", disableIpv6) { disableIpv6 = it }
                } else when (protocol) {
                    "dhcp" -> TextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = "网卡名（留空 = system）", singleLine = true)
                    "rcode" -> {
                        BasicComponent(
                            title = "回应类型",
                            summary = DNS_RCODE_OPTIONS.firstOrNull { it.value == rcode }?.label ?: rcode,
                            onClick = { picker = "rcode" },
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "常用预设", onClick = { picker = "preset" })
                }
                DialogNote("生成预览：$preview")
                if (parsed.extras.isNotEmpty()) DialogNote("未知附加参数会原样保留：${parsed.extras.joinToString("&")}")
                TextButton(text = "切换为原文编辑", onClick = {
                    raw = preview
                    rawMode = true
                })
            }
            DialogButtons(onDismiss, onConfirm = {
                val result = if (rawMode) raw.trim() else preview
                val validatedPort = if (rawMode) dnsRawPort(result) else port
                val portNumber = validatedPort.toIntOrNull()
                if (result.isBlank() || result.any { it.isWhitespace() } || result.contains('，')) {
                    showToast("DNS 服务器串不能为空，也不能包含空格或中文逗号", long = true)
                } else if (!rawMode && protocol in DNS_NETWORK_PROTOCOLS && address.isBlank()) {
                    showToast("请填写服务器地址", long = true)
                } else if (ipOnly && !rawMode && protocol !in DNS_NETWORK_PROTOCOLS) {
                    showToast("默认 DNS 必须使用 IP 地址，可选 UDP/TCP/TLS/DoH/DoQ", long = true)
                } else if (ipOnly && !isIpLiteral(if (rawMode) dnsRawHost(result) else address)) {
                    showToast("默认 DNS 服务器地址必须是 IP", long = true)
                } else if (!rawMode && (address.contains(',') || address.contains('#') || address.contains('&'))) {
                    showToast("服务器地址不能包含逗号、# 或 &", long = true)
                } else if (validatedPort.isNotBlank() && (portNumber == null || portNumber !in 1..65535)) {
                    showToast("端口必须在 1–65535 之间", long = true)
                } else {
                    onConfirm(result)
                }
            })
        }
    }

}

private fun dnsRawHost(value: String): String {
    var base = value.substringBefore('#').trim()
    val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(base)?.value.orEmpty()
    if (scheme.isNotEmpty()) base = base.removePrefix(scheme)
    if (base.contains('/')) base = base.substringBefore('/')
    return splitDnsHostPort(base).first
}

private fun dnsRawPort(value: String): String {
    var base = value.substringBefore('#').trim()
    val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(base)?.value.orEmpty()
    if (scheme.isNotEmpty()) base = base.removePrefix(scheme)
    if (base.contains('/')) base = base.substringBefore('/')
    return splitDnsHostPort(base).second
}

@Composable
private fun DnsSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    BasicComponent(
        title = title,
        endActions = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** fake-ip-filter：rule 模式为结构化四件套，blacklist / whitelist 保持域名文本列表。 */
@Composable
internal fun FakeIpRulesDialog(
    field: FormField,
    current: List<String>,
    mode: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    if (!mode.equals("rule", ignoreCase = true)) {
        ListDialog(
            field = field,
            current = current,
            candidates = emptyList(),
            pickOnly = false,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
        return
    }
    val rules = remember { mutableStateListOf<String>().also { it.addAll(current) } }
    var editing by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()
    val dragSort = rememberDragSortState(rules.size) { from, to ->
        val moved = rules.removeAt(from)
        rules.add(to, moved)
    }
    if (editing != null) {
        val index = editing!!
        FakeIpRuleItemDialog(
            initial = rules.getOrNull(index),
            onDismiss = { editing = null },
            onConfirm = { raw ->
                if (rules.indices.contains(index)) rules[index] = raw else rules.add(raw)
                editing = null
            },
        )
        return
    }
    WindowDialog(show = true, title = field.label, summary = "dns.fake-ip-filter · rule 模式", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DialogNote("从上到下匹配；末条可用 MATCH,fake-ip / MATCH,real-ip 兜底。未编辑的规则保持原文。")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .then(dragSort.containerModifier { scrollState.dispatchRawDelta(it) })
                    .verticalScroll(scrollState, enabled = dragSort.dragging < 0),
            ) {
                // 顺序由 DragSortState.order 定（拖动中实时换位）；行内容与回调按原始下标取
                dragSort.order.forEach { index ->
                    if (index !in rules.indices) return@forEach
                    val raw = rules[index]
                    DragSortRow(state = dragSort, index = index) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DragHandle(dragSort, index)
                            BasicComponent(
                                title = raw,
                                summary = fakeIpRuleSummary(raw),
                                modifier = Modifier.weight(1f),
                                endActions = {
                                    MiniIconButton(MiuixIcons.Edit, "编辑") { editing = index }
                                    Spacer(Modifier.width(8.dp))
                                    MiniIconButton(MiuixIcons.Delete, "删除") { rules.removeAt(index) }
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(text = "添加规则", onClick = { editing = rules.size })
                TextButton(text = "MATCH 兜底", onClick = { rules.add("MATCH,fake-ip") })
            }
            DialogButtons(onDismiss, onConfirm = { onConfirm(rules.toList()) })
        }
    }
}

private data class FakeIpRuleParts(val type: String, val value: String, val action: String, val raw: String? = null)

private val FAKE_IP_RULE_TYPES = listOf(
    "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD", "GEOSITE", "GEOIP", "RULE-SET", "MATCH",
).map { FormOption(it, it) }
private val FAKE_IP_ACTIONS = listOf(FormOption("fake-ip", "fake-ip（使用虚拟 IP）"), FormOption("real-ip", "real-ip（真实解析）"))

private fun parseFakeIpRule(raw: String?): FakeIpRuleParts {
    val value = raw.orEmpty().trim()
    if (value.isEmpty()) return FakeIpRuleParts("DOMAIN-SUFFIX", "", "fake-ip")
    val parts = value.split(',').map { it.trim() }
    if (parts.isEmpty()) return FakeIpRuleParts("DOMAIN-SUFFIX", "", "fake-ip", value)
    val action = parts.lastOrNull()?.lowercase()
    if (action !in setOf("fake-ip", "real-ip")) return FakeIpRuleParts("DOMAIN-SUFFIX", "", "fake-ip", value)
    val type = parts.first().uppercase()
    if (type !in FAKE_IP_RULE_TYPES.map { it.value }) return FakeIpRuleParts("DOMAIN-SUFFIX", "", "fake-ip", value)
    if (type == "MATCH") return FakeIpRuleParts(type, "", action!!)
    val match = parts.drop(1).dropLast(1).joinToString(",")
    if (match.isBlank()) return FakeIpRuleParts("DOMAIN-SUFFIX", "", "fake-ip", value)
    return FakeIpRuleParts(type, match, action!!)
}

private fun buildFakeIpRule(type: String, value: String, action: String): String =
    if (type == "MATCH") "$type,$action" else "$type,${value.trim()},$action"

private fun fakeIpRuleSummary(raw: String): String {
    val parts = parseFakeIpRule(raw)
    if (parts.raw != null) return "未识别格式 · 保留原文"
    return if (parts.type == "MATCH") "MATCH → ${parts.action}" else "${parts.type} ${parts.value} → ${parts.action}"
}

@Composable
private fun FakeIpRuleItemDialog(initial: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parsed = remember(initial) { parseFakeIpRule(initial) }
    var type by remember(initial) { mutableStateOf(parsed.type) }
    var value by remember(initial) { mutableStateOf(parsed.value) }
    var action by remember(initial) { mutableStateOf(parsed.action) }
    var rawMode by remember(initial) { mutableStateOf(parsed.raw != null) }
    var raw by remember(initial) { mutableStateOf(parsed.raw ?: initial.orEmpty()) }
    var picker by remember(initial) { mutableStateOf<String?>(null) }
    if (picker != null) {
        val choices = if (picker == "type") FAKE_IP_RULE_TYPES else FAKE_IP_ACTIONS
        OptionPickerDialog(
            title = if (picker == "type") "规则类型" else "动作",
            options = choices,
            current = if (picker == "type") type else action,
            onDismiss = { picker = null },
            onPick = { selected ->
                if (picker == "type") type = selected else action = selected
                picker = null
            },
        )
        return
    }
    WindowDialog(show = true, title = if (initial == null) "添加 fake-ip 规则" else "编辑 fake-ip 规则", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (rawMode) {
                TextField(raw, { raw = it }, modifier = Modifier.fillMaxWidth(), label = "完整规则，如 GEOSITE,gfw,real-ip", singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextButton(text = "切换到可视化", onClick = {
                    val next = parseFakeIpRule(raw)
                    if (next.raw != null) showToast("该规则暂不支持结构化拆分，请继续用原文编辑") else {
                        type = next.type; value = next.value; action = next.action; rawMode = false
                    }
                })
            } else {
                BasicComponent(title = "规则类型", summary = type, onClick = { picker = "type" })
                if (type != "MATCH") TextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), label = "匹配值", singleLine = true)
                BasicComponent(title = "动作", summary = action, onClick = { picker = "action" })
                TextButton(text = "切换到原文编辑", onClick = { raw = buildFakeIpRule(type, value, action); rawMode = true })
            }
            DialogButtons(onDismiss, onConfirm = {
                val result = if (rawMode) raw.trim() else buildFakeIpRule(type, value, action)
                if (result.isBlank() || (!rawMode && type != "MATCH" && value.isBlank())) showToast("请填写有效的规则类型和匹配值")
                else onConfirm(result)
            })
        }
    }
}

private data class InstalledAppRow(val packageName: String, val label: String, val system: Boolean)

/** 选择器中的一行：已安装应用带名称；已配置但未安装的包名只有包名。 */
private data class AppPickRow(val packageName: String, val label: String, val installed: Boolean)

private const val PICKER_ROW_KEY_PREFIX = "pkg:"
private const val PICKER_HDR_COUNT = "hdr:count"
private const val PICKER_HDR_SELECTED = "hdr:selected"
private const val PICKER_HDR_OTHERS = "hdr:others"
private const val PICKER_HDR_EMPTY = "hdr:empty"

/**
 * Android 应用多选，对齐 mihomo_box 的应用选择器：
 * - 搜索框兼作手动添加：输入应用名或包名即时过滤；输入完整包名后点「添加」（或回车）加入已选，
 *   未安装的包名同样保留；
 * - 已选应用置顶为「已选择 (N)」，其余为「其他应用 (M)」；勾选后立即重排，并以视口内一行作锚点保持位置；
 * - 标题显示清单计数（用户应用 / 全部已安装应用），搜索时附带匹配数；确定按钮实时显示已选数量。
 */
@Composable
internal fun AppListFieldDialog(
    field: FormField,
    current: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<String>().also { it.addAll(current.distinct()) } }
    var installed by remember { mutableStateOf<List<InstalledAppRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var pendingAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(context) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.MATCH_ALL)
                    .asSequence()
                    .filter { it.packageName != context.packageName }
                    .map { app ->
                        val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                            app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                        InstalledAppRow(app.packageName, app.loadLabel(pm).toString(), isSystem)
                    }
                    .distinctBy { it.packageName }
                    .sortedWith(compareBy<InstalledAppRow> { it.label.lowercase() }.thenBy { it.packageName })
                    .toList()
            }
        }
        result.onSuccess { installed = it }.onFailure { loadError = it.message ?: "读取应用列表失败" }
        loading = false
    }

    val labelOf = installed.associate { it.packageName to it.label }
    val installedNames = installed.mapTo(HashSet()) { it.packageName }
    val visibleApps = installed.filter { showSystem || !it.system }
    val query = input.trim()
    val needle = query.lowercase()
    fun matches(pkg: String): Boolean =
        needle.isEmpty() || pkg.lowercase().contains(needle) || (labelOf[pkg] ?: "").lowercase().contains(needle)

    val selectedSet = selected.toSet()
    // 已选应用不受「显示系统应用」开关影响，始终列出（否则用户看不到、也取消不掉）
    val selectedRows = selected.distinct().filter { matches(it) }
        .map { AppPickRow(it, labelOf[it] ?: it, it in installedNames) }
        .sortedWith(compareBy<AppPickRow>({ it.label.lowercase() }, { it.packageName }))
    val otherRows = visibleApps.filter { it.packageName !in selectedSet && matches(it.packageName) }
        .map { AppPickRow(it.packageName, it.label, true) }
        .sortedWith(compareBy<AppPickRow>({ it.label.lowercase() }, { it.packageName }))
    val matchedCount = visibleApps.count { matches(it.packageName) }
    val countTitle = (if (showSystem) "全部已安装应用" else "用户应用") + " (${visibleApps.size})" +
        (if (needle.isEmpty()) "" else " · 搜索匹配 ($matchedCount)")
    val canAdd = TproxyAppFilter.isPackageName(query) &&
        visibleApps.none { it.packageName == query } && query !in selectedSet

    val addManual: () -> Unit = {
        when {
            !TproxyAppFilter.isPackageName(query) -> showToast("包名格式不对，只能用字母、数字、下划线和点")
            query in selectedSet -> showToast("该包名已在已选列表中")
            else -> {
                selected.add(query)
                input = ""
                showToast("已添加 $query")
            }
        }
    }

    // 勾选 / 取消：记下视口内最靠上、且不是被点击的那一行作锚点，重排后把它拉回原位，手指附近的内容不跳动
    fun toggle(pkg: String) {
        val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val key = info.key as? String
            key != null && key.startsWith(PICKER_ROW_KEY_PREFIX) && key != PICKER_ROW_KEY_PREFIX + pkg
        }
        val anchorKey = anchor?.key as? String
        pendingAnchor = if (anchor != null && anchorKey != null) anchorKey to anchor.offset else null
        if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
    }

    val itemKeys = buildList {
        add(PICKER_HDR_COUNT)
        if (selectedRows.isNotEmpty()) {
            add(PICKER_HDR_SELECTED)
            addAll(selectedRows.map { PICKER_ROW_KEY_PREFIX + it.packageName })
            if (otherRows.isNotEmpty()) add(PICKER_HDR_OTHERS)
        }
        addAll(otherRows.map { PICKER_ROW_KEY_PREFIX + it.packageName })
    }
    LaunchedEffect(selectedSet) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        pendingAnchor = null
        val index = itemKeys.indexOf(anchor.first)
        if (index >= 0) listState.scrollToItem(index, -anchor.second)
    }

    WindowDialog(show = true, title = field.label, summary = "已选择 ${selected.size} 个应用", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    input,
                    { input = it },
                    modifier = Modifier.weight(1f),
                    label = "搜索应用名或包名，或输入完整包名后添加",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canAdd) addManual() }),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(text = "添加", enabled = canAdd, onClick = addManual)
            }
            BasicComponent(
                title = "显示系统应用",
                summary = if (showSystem) "包含系统 / 预装应用" else "默认只显示普通应用",
                endActions = { Switch(checked = showSystem, onCheckedChange = { showSystem = it }) },
                onClick = { showSystem = !showSystem },
            )
            if (loading) DialogNote("正在读取设备应用列表…")
            loadError?.let { DialogNote("应用列表读取失败：$it；仍可保留当前包名或手动填写。") }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                item(key = PICKER_HDR_COUNT) { AppPickerSectionTitle(countTitle) }
                if (selectedRows.isNotEmpty()) {
                    item(key = PICKER_HDR_SELECTED) { AppPickerSectionTitle("已选择 (${selectedRows.size})") }
                    items(selectedRows, key = { PICKER_ROW_KEY_PREFIX + it.packageName }) { row ->
                        AppChoiceRow(
                            title = row.label,
                            summary = if (row.installed) row.packageName else "未安装 · 仍会保留",
                            checked = true,
                            onToggle = { toggle(row.packageName) },
                        )
                    }
                    if (otherRows.isNotEmpty()) {
                        item(key = PICKER_HDR_OTHERS) { AppPickerSectionTitle("其他应用 (${otherRows.size})") }
                    }
                }
                items(otherRows, key = { PICKER_ROW_KEY_PREFIX + it.packageName }) { row ->
                    AppChoiceRow(
                        title = row.label,
                        summary = row.packageName,
                        checked = false,
                        onToggle = { toggle(row.packageName) },
                    )
                }
                if (selectedRows.isEmpty() && otherRows.isEmpty()) {
                    item(key = PICKER_HDR_EMPTY) {
                        DialogNote(
                            if (loadError != null || (!loading && installed.isEmpty())) "在上方输入完整包名并点「添加」"
                            else "无匹配应用"
                        )
                    }
                }
            }
            field.desc?.let { DialogNote(it) }
            DialogButtons(onDismiss, confirmLabel = "确定 (${selected.size})") { onConfirm(selected.toList()) }
        }
    }
}

@Composable
private fun AppPickerSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun AppChoiceRow(title: String, summary: String, checked: Boolean, onToggle: () -> Unit) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = { Checkbox(state = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off, onClick = onToggle) },
        onClick = onToggle,
    )
}

/** 规则集多选：候选来自当前 rule-providers，未知现有值与手工项保留。 */
@Composable
internal fun RuleSetPickDialog(
    field: FormField,
    current: List<String>,
    candidates: List<FormOption>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember { mutableStateListOf<String>().also { it.addAll(current.distinct()) } }
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    val known = remember(candidates) { candidates.mapTo(HashSet()) { it.value } }
    val currentUnknown = selected.filter { it !in known }
    val shown = candidates.filter { query.isBlank() || it.value.contains(query.trim(), ignoreCase = true) || it.label.contains(query.trim(), ignoreCase = true) }
    WindowDialog(show = true, title = field.label, summary = "已选择 ${selected.size} 个规则集", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = "搜索规则集", singleLine = true)
            if (candidates.isEmpty()) DialogNote("当前配置没有 rule-providers；你仍可以保留现有值或手动输入名称。")
            if (currentUnknown.isNotEmpty()) DialogNote("未在 rule-providers 中找到的已配置项仍会保留：${currentUnknown.joinToString()}")
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(currentUnknown, key = { "unknown:$it" }) { name ->
                    AppChoiceRow(name, "当前值 · 未匹配规则集", name in selected) {
                        if (name in selected) selected.remove(name) else selected.add(name)
                    }
                }
                items(shown, key = { it.value }) { option ->
                    AppChoiceRow(option.value, option.label, option.value in selected) {
                        if (option.value in selected) selected.remove(option.value) else selected.add(option.value)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(manual, { manual = it }, modifier = Modifier.weight(1f), label = "手动添加规则集名", singleLine = true)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "添加", onClick = {
                    val name = manual.trim()
                    if (name.isNotEmpty() && name !in selected) selected.add(name)
                    manual = ""
                })
            }
            field.desc?.let { DialogNote(it) }
            DialogButtons(onDismiss) { onConfirm(selected.toList()) }
        }
    }
}

/** headers 专用双列编辑器：HTTP 同名多值聚合成数组；其它协议按 map[string]string 写回。 */
@Composable
internal fun HeadersEditorDialog(
    field: FormField,
    current: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any?>) -> Unit,
) {
    val keys = remember { mutableStateListOf<String>().also { rows -> current.forEach { rows.add(it.first) } } }
    val values = remember { mutableStateListOf<String>().also { rows -> current.forEach { rows.add(it.second) } } }
    val duplicateNames = keys.map { it.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    WindowDialog(show = true, title = field.label, summary = "${keys.size} 行" + (if (field.arrayValues) " · 同名行合并为候选值" else ""), onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (keys.isEmpty()) DialogNote("暂无请求头，点击下方添加。")
                keys.indices.forEach { index ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = keys[index],
                            onValueChange = { keys[index] = it },
                            modifier = Modifier.weight(0.42f),
                            label = "请求头名",
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextField(
                            value = values[index],
                            onValueChange = { values[index] = it },
                            modifier = Modifier.weight(0.58f),
                            label = if (field.arrayValues) "候选值" else "值",
                            singleLine = true,
                        )
                        Spacer(Modifier.width(4.dp))
                        MiniIconButton(MiuixIcons.Delete, "删除") { keys.removeAt(index); values.removeAt(index) }
                    }
                }
            }
            if (duplicateNames.isNotEmpty() && !field.arrayValues) DialogNote("完全同名请求头会按 YAML 映射规则保留最后一项：${duplicateNames.joinToString()}")
            if (duplicateNames.isNotEmpty() && field.arrayValues) DialogNote("完全同名请求头会合并为候选值；大小写不同的键会作为两个 YAML 键保留。")
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(text = "添加请求头", onClick = { keys.add(""); values.add("") })
            }
            field.desc?.let { DialogNote(it) }
            DialogButtons(onDismiss) {
                val result = LinkedHashMap<String, Any?>()
                if (field.arrayValues) {
                    keys.indices.forEach { index ->
                        val key = keys[index].trim()
                        val value = values[index].trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            val list = (result[key] as? List<String>)?.toMutableList() ?: mutableListOf()
                            list += value
                            result[key] = list
                        }
                    }
                } else {
                    keys.indices.forEach { index ->
                        val key = keys[index].trim()
                        if (key.isNotEmpty()) result[key] = values[index].trim()
                    }
                }
                onConfirm(result)
            }
        }
    }
}

/** generic maplist：一行一个键，DNS map 的值可以直接进 DNS 服务器构建器。
 *
 * 行顺序 = 配置里的键顺序，和 mihomo_box 的 maplist 编辑器一样可以**按住行首把手拖动排序**
 * （`.rule-item` + `enableDragSort` → `reorderConfigMap`）。DNS 的「按域名分流解析」
 * （nameserver-policy / proxy-server-nameserver-policy）就是一串「域名 → 专用 DNS」，
 * 顺序影响可读性，原实现只能删了重加，这里补齐拖动排序。
 */
@Composable
internal fun MapListFieldDialog(
    field: FormField,
    current: List<FormMapListRow>,
    onDismiss: () -> Unit,
    onConfirm: (List<FormMapListEdit>) -> Unit,
) {
    val rows = remember {
        mutableStateListOf<FormMapListEdit>().also { out ->
            current.forEach { out.add(FormMapListEdit(it, it.key, it.values, it.isSequence)) }
        }
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    // 拖动中按 order 实时换位（等价参考实现的 insertBefore）；松手才把最终档位落进 rows，
    // 提交时 onConfirm(rows) 一次性写回，和其余列表页一致。
    val listScroll = rememberScrollState()
    val dragSort = rememberDragSortState(rows.size) { from, to ->
        if (from in rows.indices && to in rows.indices) {
            val moved = rows.removeAt(from)
            rows.add(to, moved)
        }
    }
    if (editing != null) {
        val index = editing!!
        val initial = rows.getOrNull(index)
        MapListEntryDialog(
            field = field,
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { key, values ->
                if (rows.indices.any { it != index && rows[it].key == key }) {
                    showToast("键「$key」已存在，请换一个名字")
                } else {
                    if (initial == null) rows.add(FormMapListEdit(null, key, values, isSequence = values.size != 1))
                    else rows[index] = initial.copy(key = key, values = values)
                    editing = null
                }
            },
        )
        return
    }
    WindowDialog(show = true, title = field.label, summary = "${rows.size} 个映射键", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (rows.isEmpty()) DialogNote("暂无条目。")
            if (rows.any { it.source?.supported == false }) {
                DialogNote("配置含有嵌套映射或别名值，P3 表单不会把它强制转换成列表；请在 YAML 编辑器中直接修改该键。")
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    // 拖着行时容器不参与滚动仲裁：外层 scrollable 退出，抢流 / 整体上滑无从发生；
                    // 边缘自动翻滚走 dispatchRawDelta（程序化滚动），不吃 userScrollEnabled
                    .then(dragSort.containerModifier { listScroll.dispatchRawDelta(it) })
                    .verticalScroll(listScroll, enabled = dragSort.dragging < 0),
            ) {
                // 顺序由 DragSortState.order 定（拖动中实时换位）；行内容与回调按原始下标取
                dragSort.order.forEach { index ->
                    if (index !in rows.indices) return@forEach
                    val row = rows[index]
                    DragSortRow(state = dragSort, index = index) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DragHandle(dragSort, index)
                            BasicComponent(
                                title = row.key,
                                summary = row.values.joinToString(", ").ifBlank { "（空）" },
                                modifier = Modifier.weight(1f),
                                endActions = {
                                    MiniIconButton(MiuixIcons.Edit, "编辑") { editing = index }
                                    Spacer(Modifier.width(8.dp))
                                    MiniIconButton(MiuixIcons.Delete, "删除") { rows.removeAt(index) }
                                },
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(text = "添加条目", onClick = { editing = rows.size })
            }
            field.desc?.let { DialogNote(it) }
            DialogButtons(onDismiss, confirmLabel = if (rows.any { it.source?.supported == false }) null else "确定") {
                onConfirm(rows.toList())
            }
        }
    }
}

@Composable
private fun MapListEntryDialog(
    field: FormField,
    initial: FormMapListEdit?,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit,
) {
    var key by remember(initial) { mutableStateOf(initial?.key.orEmpty()) }
    var values by remember(initial) { mutableStateOf(initial?.values.orEmpty()) }
    var dnsBuilder by remember(initial) { mutableStateOf(false) }
    val dnsValueScroll = rememberScrollState()
    val dnsValueSort = rememberDragSortState(values.size) { from, to ->
        val moved = values[from]
        values = values.toMutableList().also { it.removeAt(from); it.add(to, moved) }
    }
    if (dnsBuilder) {
        DnsServerListDialog(
            title = "${field.label} · DNS 服务器",
            ipOnly = false,
            current = values,
            onDismiss = { dnsBuilder = false },
            onConfirm = { values = it; dnsBuilder = false },
        )
        return
    }
    WindowDialog(show = true, title = if (initial == null) "添加映射条目" else "编辑映射条目", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = field.keyPlaceholder ?: "键", singleLine = true)
            if (field.dns) {
                DialogNote("值为 DNS 服务器列表；可使用可视化构建器添加、编辑和排序。")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .then(dnsValueSort.containerModifier { dnsValueScroll.dispatchRawDelta(it) })
                        .verticalScroll(dnsValueScroll, enabled = dnsValueSort.dragging < 0),
                ) {
                    // 顺序由 DragSortState.order 定（拖动中实时换位）；行内容与回调按原始下标取
                    dnsValueSort.order.forEach { index ->
                        if (index !in values.indices) return@forEach
                        val value = values[index]
                        DragSortRow(state = dnsValueSort, index = index) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                DragHandle(dnsValueSort, index)
                                BasicComponent(
                                    title = value,
                                    modifier = Modifier.weight(1f),
                                    endActions = {
                                        MiniIconButton(MiuixIcons.Delete, "删除") {
                                            values = values.toMutableList().also { it.removeAt(index) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "可视化 DNS", onClick = { dnsBuilder = true })
                }
                TextField(
                    value = values.joinToString(", "),
                    onValueChange = { values = it.split(',').map(String::trim).filter(String::isNotEmpty) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "服务器列表（逗号分隔）",
                    singleLine = false,
                )
            } else {
                TextField(
                    value = values.joinToString(", "),
                    onValueChange = { values = it.split(',').map(String::trim).filter(String::isNotEmpty) },
                    modifier = Modifier.fillMaxWidth(),
                    label = if (field.path == "hosts") "IP 地址（多个用逗号分隔）" else "值（多个用逗号分隔）",
                    singleLine = false,
                )
            }
            DialogNote("多个值按列表写回；单个值保留原来的标量 / 列表形态。")
            DialogButtons(onDismiss) {
                val name = key.trim()
                if (name.isEmpty()) showToast("请填写键名") else onConfirm(name, values.map(String::trim).filter(String::isNotEmpty))
            }
        }
    }
}
