package top.yukonga.mishka.custom.forms

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 出站代理「解析节点」（对齐 mihomo_box `proxy-uri.js` 的 Kotlin 版）：
 * 把粘贴的分享链接（SS / VMess / VLESS / Trojan / Hysteria2 / TUIC）或 Clash/Mihomo YAML 节点
 * 解析成 `proxies` 序列可写入的映射。纯本地解析，无网络、无 eval；解析不了的内容按行报错，不猜。
 *
 * 另含 `ensureProxiesRoot`：file 类型订阅源文件保存前的兜底——用户只贴「- name: …」节点列表或
 * 单个节点时自动补 `proxies:` 根（mihomo 要求订阅文件必须是 proxies 列表）；其余内容一律不动。
 */

/** 解析失败（消息即用户可见文案）。 */
internal class ProxyUriError(message: String) : Exception(message)

internal data class ProxyImportAdded(val line: Int, val name: String, val renamed: Boolean, val warnings: List<String>)

internal data class ProxyImportError(val line: Int, val message: String, val input: String)

internal data class ProxyImportResult(
    val proxies: List<Map<String, Any?>>,
    val added: List<ProxyImportAdded>,
    val errors: List<ProxyImportError>,
    val skipped: Int,
    val total: Int,
)

internal object ProxyUri {

    private const val MAX_TEXT = 1024 * 1024
    private const val MAX_NODES = 500

    private val SCHEME_HEAD = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val CIPHER_RE = Regex("^[a-zA-Z0-9-]+$")
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val REALITY_PUBKEY_RE = Regex("^[A-Za-z0-9_-]{43}$")
    private val REALITY_SID_RE = Regex("^(?:[0-9a-fA-F]{2}){0,8}$")
    private val BASE64_BODY_RE = Regex("^[A-Za-z0-9+/]*={0,2}$")
    private val NUM_RE = Regex("^\\d+$")

    // ---------------------------------------------------------------- 入口

    /** 整批解析：URI 每行一条（一行多条按空白拆），或整体按 YAML 节点收。同名自动加序号。 */
    fun parseAll(input: String, existingNames: Collection<String> = emptyList()): ProxyImportResult {
        if (input.length > MAX_TEXT || input.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT) {
            throw ProxyUriError("内容过大，请分批导入（每批最多 1 MiB 文本）")
        }
        if (input.isBlank()) throw ProxyUriError("请先粘贴节点 URI 或 YAML")
        val names = LinkedHashSet(existingNames)
        val seen = HashSet<String>()
        val proxies = ArrayList<Map<String, Any?>>()
        val added = ArrayList<ProxyImportAdded>()
        val errors = ArrayList<ProxyImportError>()
        var skipped = 0
        val result = if (looksLikeYamlNodes(input)) {
            val nodes = yamlNodes(input)
            if (nodes.size > MAX_NODES) throw ProxyUriError("每批最多 500 个节点，请分批导入")
            nodes.forEachIndexed { i, proxy ->
                val key = renderKey(proxy)
                if (!seen.add(key)) { skipped++; return@forEachIndexed }
                try {
                    val original = proxy["name"].toString()
                    val finalName = uniquify(original, names)
                    val p = LinkedHashMap(proxy)
                    p["name"] = finalName
                    proxies.add(p)
                    added.add(ProxyImportAdded(i + 1, finalName, finalName != original, emptyList()))
                } catch (e: Exception) {
                    errors.add(ProxyImportError(i + 1, e.message ?: "解析失败", proxy["name"]?.toString().orEmpty()))
                }
            }
            nodes.size
        } else {
            val entries = ArrayList<Pair<String, Int>>()
            input.split(Regex("\\r?\\n")).forEachIndexed { i, line ->
                line.trim().split(Regex("\\s+(?=[a-zA-Z][a-zA-Z0-9+.-]*://)")).filter { it.isNotEmpty() }
                    .forEach { entries.add(it to i + 1) }
            }
            if (entries.isEmpty()) throw ProxyUriError("请先粘贴节点 URI 或 YAML")
            if (entries.size > MAX_NODES) throw ProxyUriError("每批最多 500 个节点，请分批导入")
            for ((uri, line) in entries) {
                if (!seen.add(uri)) { skipped++; continue }
                try {
                    val (proxy, warnings) = parseOne(uri)
                    val original = proxy["name"].toString()
                    val finalName = uniquify(original, names)
                    val p = LinkedHashMap(proxy)
                    p["name"] = finalName
                    proxies.add(p)
                    added.add(ProxyImportAdded(line, finalName, finalName != original, warnings))
                } catch (e: Exception) {
                    errors.add(ProxyImportError(line, e.message ?: "解析失败", uri))
                }
            }
            entries.size
        }
        return ProxyImportResult(proxies, added, errors, skipped, result)
    }

    /** 单条分享链接 → 节点映射（含 name）+ 警告。 */
    fun parseOne(uri: String): Pair<Map<String, Any?>, List<String>> {
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(uri)
            ?: throw ProxyUriError("不是有效的节点 URI")
        var type = m.groupValues[1].lowercase()
        if (type == "hy2") type = "hysteria2"
        val warnings = ArrayList<String>()
        val parsed = when (type) {
            "ss" -> parseSS(m.groupValues[2], warnings)
            "vmess" -> parseVMess(m.groupValues[2], warnings)
            "vless", "trojan", "hysteria2", "tuic" -> parseStandard(uri, type, warnings)
            else -> throw ProxyUriError("暂不支持此协议（支持 SS / VMess / VLESS / Trojan / Hysteria2 / TUIC）")
        }
        val server = parsed.second["server"]?.toString().orEmpty()
        val port = parsed.second["port"]?.toString().orEmpty()
        var name = (parsed.first.ifBlank { "$type-$server:$port" }).replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim()
        if (name.length > 200) throw ProxyUriError("节点名称过长（最多 200 字符）")
        if (name.isEmpty()) name = "$type-out"
        val proxy = LinkedHashMap<String, Any?>()
        proxy["name"] = name
        proxy.putAll(parsed.second)
        return proxy to warnings
    }

    /** 输入整体像 YAML 节点（而不是 URI 列表）？（参考实现 looksLikeYamlNodes） */
    fun looksLikeYamlNodes(input: String): Boolean {
        val first = input.split(Regex("\\r?\\n")).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (SCHEME_HEAD.containsMatchIn(first)) return false
        // 单排流式节点（`- { name: … }` / 裸 `{…}` / 整体 `[…]`）没有「proxies:」也没有块式「- name:」，
        // 只按块式特征判定会漏认成 URI 文本。
        return Regex("^\\s*(proxies\\s*:|-\\s+(name|type)\\s*:|(name|type)\\s*:|-\\s*[{\\[]|[{\\[])", RegexOption.MULTILINE)
            .containsMatchIn(input)
    }

    /** YAML 文本 → 节点映射列表（支持 `proxies:` 包装、裸列表、单节点；流式写法同样收）。 */
    fun yamlNodes(input: String): List<Map<String, Any?>> {
        val doc = YamlDoc.parse(input)
        var root = doc.root ?: throw ProxyUriError("YAML 内容不是节点")
        // 引擎把顶层序列 / 标量包进虚拟映射（`__seq__` / `__scalar__`），先解开拿到真实根
        if (root.kind == YamlNode.Kind.MAP && root.entries.size == 1) {
            val only = root.entries.first()
            if (only.key == "__seq__" || only.key == "__scalar__") {
                root = only.node ?: throw ProxyUriError("YAML 内容不是节点")
            }
        }
        fun isNodeMap(n: YamlNode?): Boolean =
            n != null && n.kind == YamlNode.Kind.MAP &&
                doc.findEntry(n, "name") != null && doc.findEntry(n, "type") != null
        val list: List<YamlNode> = when {
            root.kind == YamlNode.Kind.SEQ -> root.items
            root.kind == YamlNode.Kind.MAP -> {
                val proxiesEntry = doc.findEntry(root, "proxies")
                when {
                    proxiesEntry == null -> {
                        if (isNodeMap(root)) listOf(root)
                        else throw ProxyUriError("YAML 中没有可导入的节点（需要 proxies 列表，或带 name/type 的节点）")
                    }
                    proxiesEntry.node?.kind == YamlNode.Kind.SEQ -> proxiesEntry.node.items
                    isNodeMap(proxiesEntry.node) -> listOf(proxiesEntry.node!!)
                    else -> throw ProxyUriError("proxies 字段必须是节点列表")
                }
            }
            else -> throw ProxyUriError("YAML 内容不是节点")
        }
        val out = ArrayList<Map<String, Any?>>()
        list.forEachIndexed { i, item ->
            if (item.kind == YamlNode.Kind.RAW && doc.isNullText(item)) return@forEachIndexed
            if (item.kind != YamlNode.Kind.MAP) throw ProxyUriError("第 ${i + 1} 个条目不是节点对象")
            val proxy = LinkedHashMap(PlainYaml.toPlain(doc, item))
            proxy.remove("proxies") // 顶层 / 节点上的 proxies 包装都丢掉
            val name = proxy["name"]?.toString().orEmpty().replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim()
            if (name.length > 200) throw ProxyUriError("第 ${i + 1} 个节点名称过长（最多 200 字符）")
            val type = proxy["type"]?.toString()?.trim().orEmpty()
            if (type.isEmpty()) throw ProxyUriError("第 ${i + 1} 个节点缺少 type")
            proxy["name"] = name.ifEmpty { "$type-out" }
            proxy["type"] = type
            out.add(proxy)
        }
        if (out.isEmpty()) throw ProxyUriError("YAML 中没有有效节点")
        if (out.size > MAX_NODES) throw ProxyUriError("每批最多 500 个节点，请分批导入")
        return out
    }

    /**
     * file 订阅文件保存前的兜底（参考实现 ensureProxiesRoot）：裸节点列表 / 单节点自动补 `proxies:` 根。
     * URI 列表 / 已带 proxies / 解析不了的内容原样返回。返回 `文本 to 是否有改动`。
     */
    fun ensureProxiesRoot(src: String): Pair<String, Boolean> {
        if (src.isBlank()) return src to false
        val first = src.split(Regex("\\r?\\n")).firstOrNull { it.trim().isNotEmpty() && !it.trim().startsWith("#") }?.trim().orEmpty()
        if (SCHEME_HEAD.containsMatchIn(first)) return src to false // URI 列表
        val doc = YamlDoc.parse(src)
        var root = doc.root ?: return src to false
        // 顶层序列 / 标量被引擎包进 `__seq__` / `__scalar__` 虚拟映射，先解开
        if (root.kind == YamlNode.Kind.MAP && root.entries.size == 1) {
            val only = root.entries.first()
            if (only.key == "__seq__" || only.key == "__scalar__") {
                root = only.node ?: return src to false
            }
        }
        fun isNodeMap(n: YamlNode?): Boolean =
            n != null && n.kind == YamlNode.Kind.MAP && doc.findEntry(n, "type") != null
        if (root.kind == YamlNode.Kind.SEQ && root.items.any { isNodeMap(it) }) {
            // 先去掉内容原有的公共缩进，再统一缩进两格挂到 proxies: 下（保留注释与书写风格）
            val rows = src.replace(Regex("\\s+$"), "").split(Regex("\\r?\\n"))
            fun lead(l: String) = l.length - l.trimStart(' ').length
            fun isCmt(l: String) = l.trim().startsWith("#")
            var common = Int.MAX_VALUE
            for (l in rows) if (l.trim().isNotEmpty() && !isCmt(l)) common = minOf(common, lead(l))
            if (common == Int.MAX_VALUE) common = 0
            val body = rows.joinToString("\n") { l ->
                if (l.trim().isEmpty()) "" else "  " + l.substring(minOf(common, lead(l)))
            }
            return "proxies:\n$body\n" to true
        }
        if (isNodeMap(root) && doc.findEntry(root, "name") != null && doc.findEntry(root, "proxies") == null) {
            val plain = PlainYaml.toPlain(doc, root)
            // 复用引擎的渲染：把单节点写进一份临时文档的 proxies 列表再导出
            val shell = YamlPatch.setValue(YamlDoc.parse("proxies:\n"), listOf("proxies"), listOf(plain))
            return shell.dump().let { if (it.endsWith("\n")) it else "$it\n" } to true
        }
        return src to false
    }

    // ---------------------------------------------------------------- SS

    private fun parseSS(bodyIn: String, warnings: MutableList<String>): Pair<String, Map<String, Any?>> {
        var body = bodyIn
        val hash = body.indexOf('#')
        val name = if (hash < 0) "" else unescape(body.substring(hash + 1))
        if (hash >= 0) body = body.substring(0, hash)
        val q = body.indexOf('?')
        val params = parseQuery(if (q < 0) "" else body.substring(q + 1))
        var main = if (q < 0) body else body.substring(0, q)
        if (!main.contains('@')) main = base64(main)
        val at = main.lastIndexOf('@')
        if (at < 0) throw ProxyUriError("SS 缺少认证信息或服务器地址")
        var auth = unescape(main.substring(0, at))
        if (!auth.contains(':')) auth = base64(auth)
        val colon = auth.indexOf(':')
        if (colon <= 0 || colon == auth.length - 1) throw ProxyUriError("SS 缺少加密方式或密码")
        val cipher = auth.substring(0, colon)
        if (!CIPHER_RE.matches(cipher)) throw ProxyUriError("SS 加密方式格式不正确")
        val ep = endpointOf(hostPortOf("ss://" + main.substring(at + 1)))
        val proxy = linkedMapOf<String, Any?>(
            "type" to "ss",
            "server" to ep.first,
            "port" to ep.second,
            "cipher" to cipher,
            "password" to auth.substring(colon + 1),
            "udp" to true,
        )
        val plugin = params["plugin"]
        if (plugin != null) {
            val parts = plugin.split(';')
            val pluginName = parts.first()
            if (pluginName !in listOf("obfs-local", "simple-obfs", "obfs", "v2ray-plugin")) {
                throw ProxyUriError("暂不支持此 SS 插件，请手动配置")
            }
            val opts = LinkedHashMap<String, Any?>()
            for (part in parts.drop(1)) {
                val i = part.indexOf('=')
                val key = if (i < 0) part else part.substring(0, i)
                val value = if (i < 0) "true" else part.substring(i + 1)
                if (key.isEmpty()) continue
                val mapped = when (key) {
                    "obfs" -> "mode"
                    "obfs-host" -> "host"
                    "obfs-uri" -> "path"
                    else -> key
                }
                if (mapped !in listOf("mode", "host", "path", "tls", "mux", "skip-cert-verify")) {
                    throw ProxyUriError("暂不支持此 SS 插件参数，请手动配置")
                }
                opts[mapped] = if (mapped in listOf("tls", "mux", "skip-cert-verify")) boolOf(value) else value
            }
            val mappedName = if (pluginName == "v2ray-plugin") "v2ray-plugin" else "obfs"
            if (mappedName == "obfs" && opts["mode"] !in listOf("http", "tls")) throw ProxyUriError("SS obfs 插件缺少有效模式")
            if (mappedName == "v2ray-plugin") {
                val mode = opts["mode"]?.toString()
                if (!mode.isNullOrEmpty() && mode != "websocket") throw ProxyUriError("暂不支持此 v2ray-plugin 模式")
                opts["mode"] = "websocket"
            }
            proxy["plugin"] = mappedName
            proxy["plugin-opts"] = opts
        }
        if (params.keys.any { it != "plugin" }) warnings.add("含未映射的 SS 参数，请在编辑器核对")
        return name to proxy
    }

    // ---------------------------------------------------------------- VMess

    private val VMESS_KNOWN = setOf(
        "v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path",
        "tls", "sni", "alpn", "fp", "allowInsecure", "serviceName",
    )

    private fun parseVMess(bodyIn: String, warnings: MutableList<String>): Pair<String, Map<String, Any?>> {
        val hash = bodyIn.indexOf('#')
        val fragment = if (hash < 0) "" else unescape(bodyIn.substring(hash + 1))
        val o: Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(base64(if (hash < 0) bodyIn else bodyIn.substring(0, hash))) as? Map<String, Any?>
                ?: throw ProxyUriError("VMess JSON 应为对象")
        } catch (e: ProxyUriError) {
            throw e
        } catch (e: Exception) {
            throw ProxyUriError("VMess 需要 Base64 编码的 JSON 分享链接")
        }
        for (key in listOf("ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path", "tls", "sni", "fp", "allowInsecure", "serviceName")) {
            val v = o[key] ?: continue
            if (v !is String && v !is Long && v !is Double && v !is Boolean) throw ProxyUriError("VMess 字段类型不正确")
        }
        val alpn = o["alpn"]
        if (alpn != null && alpn !is String && !(alpn is List<*> && alpn.all { it is String })) {
            throw ProxyUriError("VMess ALPN 字段类型不正确")
        }
        val proxy = LinkedHashMap<String, Any?>()
        proxy["type"] = "vmess"
        val ep = endpointOf(hostPortOf2(o["add"]?.toString(), o["port"]))
        proxy["server"] = ep.first
        proxy["port"] = ep.second
        proxy["uuid"] = uuidOf(o["id"]?.toString())
        proxy["alterId"] = intOf(o["aid"]?.toString() ?: "0", 0, 65535, "alterId")
        val cipher = o["scy"]?.toString()?.ifEmpty { null } ?: "auto"
        proxy["cipher"] = cipher
        proxy["udp"] = true
        if (cipher !in listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) {
            throw ProxyUriError("不支持此 VMess 加密方式")
        }
        val tlsRaw = o["tls"]?.toString()
        if (!tlsRaw.isNullOrEmpty() && tlsRaw !in listOf("tls", "none")) throw ProxyUriError("不支持此 VMess TLS 类型")
        var net = o["net"]?.toString()?.ifEmpty { null } ?: "tcp"
        val headType = o["type"]?.toString()
        if (net == "tcp" && headType == "http") net = "http"
        else if (!headType.isNullOrEmpty() && headType !in listOf("none", "gun")) throw ProxyUriError("不支持此 VMess 传输伪装")
        transport(proxy, net, o["host"]?.toString().orEmpty(), o["path"]?.toString().orEmpty(), o["serviceName"]?.toString().orEmpty())
        tls(proxy, enabled = tlsRaw == "tls", sni = o["sni"]?.toString(), alpn = alpn, fp = o["fp"]?.toString(), insecure = o["allowInsecure"]?.toString())
        if (o.keys.any { it !in VMESS_KNOWN }) warnings.add("含未映射的 VMess 字段，请在编辑器核对")
        return (fragment.ifEmpty { o["ps"]?.toString().orEmpty() }) to proxy
    }

    // ---------------------------------------------------------------- VLESS / Trojan / Hysteria2 / TUIC

    private fun parseStandard(uri: String, type: String, warnings: MutableList<String>): Pair<String, Map<String, Any?>> {
        val u = parseUri(uri)
        val q = u.params
        val used = HashSet<String>()
        fun param(vararg names: String): String {
            names.forEach { used.add(it) }
            return names.mapNotNull { q[it]?.takeIf(String::isNotEmpty) }.firstOrNull().orEmpty()
        }
        val proxy = LinkedHashMap<String, Any?>()
        proxy["type"] = type
        val defaultPort = if (type in listOf("trojan", "hysteria2", "tuic")) "443" else ""
        val ep = endpointOf(hostPortOf2(u.host, u.port.ifEmpty { defaultPort }))
        proxy["server"] = ep.first
        proxy["port"] = ep.second
        proxy["udp"] = true
        val username = unescape(u.username)
        val password = unescape(u.password)
        if (type == "vless") {
            if (u.hasPassword && u.password.isNotEmpty()) throw ProxyUriError("VLESS 认证部分应仅含 UUID")
            proxy["uuid"] = uuidOf(username)
            val encryption = param("encryption")
            if (encryption.isNotEmpty() && encryption != "none") throw ProxyUriError("暂不支持此 VLESS encryption")
            val flow = param("flow")
            if (flow.isNotEmpty() && flow != "xtls-rprx-vision") throw ProxyUriError("暂不支持此 VLESS flow")
            if (flow.isNotEmpty()) proxy["flow"] = flow
        } else if (type == "tuic") {
            proxy["uuid"] = uuidOf(username)
            if (password.isEmpty()) throw ProxyUriError("TUIC 缺少密码")
            proxy["password"] = password
        } else {
            val pw = username + if (u.hasPassword && password.isNotEmpty()) ":$password" else ""
            if (pw.isEmpty()) throw ProxyUriError("节点缺少密码")
            proxy["password"] = pw
        }
        if (u.path.isNotEmpty() && u.path != "/") throw ProxyUriError("传输路径应放在 URI 的 path 参数中")
        val security = param("security")
        if (type in listOf("hysteria2", "tuic") && security.isNotEmpty() && security != "tls") throw ProxyUriError("此协议只支持 TLS")
        if (type == "vless" || type == "trojan") {
            if (security.isNotEmpty() && security !in listOf("none", "tls", "reality")) throw ProxyUriError("不支持此 security 类型")
            if (type == "trojan" && security == "none") throw ProxyUriError("Trojan 不支持关闭 TLS")
            transport(proxy, param("type", "network").ifEmpty { "tcp" }, param("host"), param("path"), param("serviceName"))
            // 与参考实现一致：只有 grpc 才看 mode，其它传输层出现 mode 记入「未映射参数」警告
            if (proxy["network"] == "grpc") {
                val mode = param("mode")
                if (mode.isNotEmpty() && mode != "gun") throw ProxyUriError("暂不支持此 gRPC mode")
            }
        }
        tls(
            proxy,
            enabled = if (type == "vless") security in listOf("tls", "reality") else null,
            sni = param("sni", "peer", "servername").ifEmpty { null },
            alpn = param("alpn").ifEmpty { null },
            fp = param("fp").ifEmpty { null },
            insecure = param("allowInsecure", "insecure", "skip-cert-verify").ifEmpty { null },
        )
        if (security == "reality") {
            if (type !in listOf("vless", "trojan")) throw ProxyUriError("此协议不支持 Reality")
            val key = param("pbk", "public-key")
            val sid = param("sid", "short-id")
            if (!REALITY_PUBKEY_RE.matches(key)) throw ProxyUriError("Reality 公钥缺失或格式不正确")
            if (!REALITY_SID_RE.matches(sid)) throw ProxyUriError("Reality short-id 格式不正确")
            proxy["reality-opts"] = linkedMapOf<String, Any?>("public-key" to key, "short-id" to sid)
            if (proxy["client-fingerprint"] == null) proxy["client-fingerprint"] = "chrome"
        }
        if (type == "hysteria2") {
            val obfs = param("obfs")
            val obfsPassword = param("obfs-password")
            if (obfs.isNotEmpty()) {
                if (obfs != "salamander" || obfsPassword.isEmpty()) throw ProxyUriError("Hysteria2 混淆需要 salamander 和 obfs-password")
                proxy["obfs"] = obfs
                proxy["obfs-password"] = obfsPassword
            }
            if (param("mport").isNotEmpty()) throw ProxyUriError("暂不支持端口跳跃链接，请手动配置 ports")
        }
        if (type == "tuic") {
            val cc = param("congestion_control", "congestion-controller")
            val relay = param("udp_relay_mode", "udp-relay-mode")
            if (cc.isNotEmpty()) {
                if (cc !in listOf("cubic", "bbr", "new_reno")) throw ProxyUriError("TUIC 拥塞控制参数不正确")
                proxy["congestion-controller"] = cc
            }
            if (relay.isNotEmpty()) {
                if (relay !in listOf("native", "quic")) throw ProxyUriError("TUIC UDP 转发模式不正确")
                proxy["udp-relay-mode"] = relay
            }
            val reduce = param("reduce_rtt", "reduce-rtt")
            if (reduce.isNotEmpty()) proxy["reduce-rtt"] = boolOf(reduce)
            val disable = param("disable_sni", "disable-sni")
            if (disable.isNotEmpty()) proxy["disable-sni"] = boolOf(disable)
        }
        if (q.keys.any { it !in used }) warnings.add("含未映射的 URI 参数，请在编辑器核对")
        return unescape(u.hash) to proxy
    }

    // ---------------------------------------------------------------- 公共小件

    private fun transport(proxy: MutableMap<String, Any?>, netIn: String?, host: String, path: String, service: String) {
        val net = netIn?.ifEmpty { null } ?: "tcp"
        if (net !in listOf("tcp", "ws", "http", "h2", "grpc")) throw ProxyUriError("暂不支持此传输方式，请手动新建节点")
        proxy["network"] = net
        when (net) {
            "ws" -> {
                val m = linkedMapOf<String, Any?>("path" to path.ifEmpty { "/" })
                if (host.isNotEmpty()) m["headers"] = linkedMapOf<String, Any?>("Host" to host)
                proxy["ws-opts"] = m
            }
            "http" -> {
                val m = linkedMapOf<String, Any?>("path" to path.ifEmpty { "/" }.split(','))
                if (host.isNotEmpty()) m["headers"] = linkedMapOf<String, Any?>("Host" to host.split(','))
                proxy["http-opts"] = m
            }
            "h2" -> {
                val m = linkedMapOf<String, Any?>("path" to path.ifEmpty { "/" })
                if (host.isNotEmpty()) m["host"] = host.split(',')
                proxy["h2-opts"] = m
            }
            "grpc" -> proxy["grpc-opts"] = linkedMapOf<String, Any?>("grpc-service-name" to service.ifEmpty { path })
        }
    }

    private fun tls(proxy: MutableMap<String, Any?>, enabled: Boolean?, sni: String?, alpn: Any?, fp: String?, insecure: String?) {
        if (enabled != null) proxy["tls"] = enabled
        if (!sni.isNullOrEmpty()) proxy[if (proxy["type"] in listOf("vmess", "vless")) "servername" else "sni"] = sni
        if (alpn != null) {
            val items = if (alpn is List<*>) alpn.map { it.toString() } else alpn.toString().split(',')
            val clean = items.map { it.trim() }.filter { it.isNotEmpty() }
            if (clean.isNotEmpty()) proxy["alpn"] = clean
        }
        if (!fp.isNullOrEmpty()) proxy["client-fingerprint"] = fp
        if (!insecure.isNullOrEmpty()) proxy["skip-cert-verify"] = boolOf(insecure)
    }

    /** `scheme://[user[:pass]@]host[:port][/path][?query][#hash]` 的宽容解析（参考实现用 WHATWG URL）。 */
    private data class ParsedUri(
        val scheme: String,
        val username: String,
        val password: String,
        val hasPassword: Boolean,
        val host: String,
        val port: String,
        val path: String,
        val hash: String,
        val params: Map<String, String>,
    )

    private fun parseUri(uri: String): ParsedUri {
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(uri)
            ?: throw ProxyUriError("URI 地址或端口格式不正确")
        var rest = m.groupValues[2]
        var hash = ""
        rest.indexOf('#').takeIf { it >= 0 }?.let { hash = rest.substring(it + 1); rest = rest.substring(0, it) }
        var query = ""
        rest.indexOf('?').takeIf { it >= 0 }?.let { query = rest.substring(it + 1); rest = rest.substring(0, it) }
        var path = ""
        rest.indexOf('/').takeIf { it >= 0 }?.let { path = rest.substring(it); rest = rest.substring(0, it) }
        var username = ""
        var password = ""
        var hasPassword = false
        rest.lastIndexOf('@').takeIf { it >= 0 }?.let {
            val ui = rest.substring(0, it)
            rest = rest.substring(it + 1)
            val c = ui.indexOf(':')
            if (c >= 0) { username = ui.substring(0, c); password = ui.substring(c + 1); hasPassword = true } else username = ui
        }
        var host: String
        var port = ""
        if (rest.startsWith("[")) {
            val e = rest.indexOf(']')
            if (e < 0) throw ProxyUriError("URI 地址或端口格式不正确")
            host = rest.substring(1, e)
            val after = rest.substring(e + 1)
            when {
                after.isEmpty() -> {}
                after.startsWith(":") -> port = after.substring(1)
                else -> throw ProxyUriError("URI 地址或端口格式不正确")
            }
        } else {
            val c = rest.indexOf(':')
            if (c >= 0) { host = rest.substring(0, c); port = rest.substring(c + 1) } else host = rest
        }
        if (host.isEmpty()) throw ProxyUriError("URI 地址或端口格式不正确")
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            if (k.isEmpty()) continue
            val key = try { unescape(k) } catch (e: ProxyUriError) { k }
            val value = try { unescape(v) } catch (e: ProxyUriError) { v }
            params.putIfAbsent(key, value)
        }
        return ParsedUri(m.groupValues[1].lowercase(), username, password, hasPassword, host, port, path, hash, params)
    }

    private fun parseQuery(query: String): Map<String, String> =
        if (query.isEmpty()) emptyMap() else parseUri("x://h?$query").params

    /** host/port 校验（`endpoint`）：去 IPv6 方括号、拒空白与控制字符。 */
    private fun endpointOf(hp: Pair<String, String>): Pair<String, Long> {
        val server = hp.first.trim('[', ']')
        if (server.isEmpty() || Regex("[\\s\\u0000-\\u001f\\u007f/@?#]").containsMatchIn(server)) {
            throw ProxyUriError("服务器地址不正确")
        }
        return server to intOf(hp.second, 1, 65535, "端口").toLong()
    }

    /** 从 `scheme://host[:port]` 里取 host/port（SS 的 @ 之后部分）。 */
    private fun hostPortOf(prefixAndRest: String): Pair<String, String> {
        val u = parseUri(prefixAndRest)
        if (u.username.isNotEmpty() || u.hasPassword || (u.path.isNotEmpty() && u.path != "/")) {
            throw ProxyUriError("SS 服务器地址格式不正确")
        }
        return u.host to u.port
    }

    private fun hostPortOf2(host: String?, port: Any?): Pair<String, String> =
        (host?.toString().orEmpty()) to when (port) {
            null -> ""
            is Long -> port.toString()
            is Double -> if (port == port.toLong().toDouble()) port.toLong().toString() else port.toString()
            else -> port.toString()
        }

    private fun intOf(value: String?, min: Long, max: Long, label: String): Long {
        val t = value?.toString().orEmpty()
        if (!NUM_RE.matches(t)) throw ProxyUriError("${label}不正确")
        val n = t.toLongOrNull() ?: throw ProxyUriError("${label}不正确")
        if (n < min || n > max) throw ProxyUriError("${label}不正确")
        return n
    }

    private fun boolOf(value: String): Boolean = when {
        Regex("^(1|true)$", RegexOption.IGNORE_CASE).matches(value) -> true
        Regex("^(0|false)$", RegexOption.IGNORE_CASE).matches(value) -> false
        else -> throw ProxyUriError("布尔参数应为 true/false 或 1/0")
    }

    private fun uuidOf(value: String?): String {
        val v = value?.toString().orEmpty()
        if (!UUID_RE.matches(v)) throw ProxyUriError("UUID 格式不正确，应为 8-4-4-4-12 位十六进制")
        return v
    }

    /** decodeURIComponent 等价：只解 %XX，不把 '+' 当空格；非法 UTF-8 直接报错。 */
    private fun unescape(value: String): String {
        if (!value.contains('%')) return value
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) throw ProxyUriError("URI 百分号编码不正确")
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) throw ProxyUriError("URI 百分号编码不正确")
                out.write(hi * 16 + lo)
                i += 3
            } else {
                val bytes = c.toString().toByteArray(StandardCharsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i++
            }
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (e: Exception) {
            throw ProxyUriError("Base64 或 UTF-8 内容不正确")
        }
    }

    /** URL-safe Base64（补 padding）→ UTF-8 文本；非法编码报用户可见错误。 */
    private fun base64(value: String): String {
        val s = unescape(value).replace('-', '+').replace('_', '/')
        if (!BASE64_BODY_RE.matches(s) || s.length % 4 == 1) throw ProxyUriError("Base64 编码不正确")
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        val bytes = try {
            java.util.Base64.getDecoder().decode(padded)
        } catch (e: Exception) {
            throw ProxyUriError("Base64 编码不正确")
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw ProxyUriError("Base64 或 UTF-8 内容不正确")
        }
    }

    private fun uniquify(name: String, names: MutableSet<String>): String {
        var candidate = name
        var index = 2
        while (candidate in names) candidate = "$name (${index++})"
        names.add(candidate)
        return candidate
    }

    /** 去重键：映射内容按 key 排序拼串（等价参考实现的 JSON.stringify）。 */
    private fun renderKey(proxy: Map<String, Any?>): String =
        proxy.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> "$k=${renderValue(v)}" }

    private fun renderValue(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> "{${v.entries.joinToString(",") { "${it.key}:${renderValue(it.value)}" }}}"
        is List<*> -> "[${v.joinToString(",") { renderValue(it) }}]"
        else -> v.toString()
    }
}

/** VMess 分享链接里的最小 JSON 读取器（对象 / 数组 / 字符串 / 数字 / 布尔 / null）。 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.eof()) throw IllegalArgumentException("JSON 尾部有多余内容")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun eof() = i >= s.length
        fun skipWs() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun value(): Any? {
            skipWs()
            if (eof()) throw IllegalArgumentException("JSON 不完整")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("JSON 格式不正确")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("JSON 格式不正确")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!eof() && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                if (eof() || s[i] != ':') throw IllegalArgumentException("JSON 格式不正确")
                i++
                out[key] = value()
                skipWs()
                if (eof()) throw IllegalArgumentException("JSON 不完整")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("JSON 格式不正确")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (!eof() && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWs()
                if (eof()) throw IllegalArgumentException("JSON 不完整")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("JSON 格式不正确")
                }
            }
        }

        private fun str(): String {
            if (eof() || s[i] != '"') throw IllegalArgumentException("JSON 格式不正确")
            i++
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw IllegalArgumentException("JSON 字符串未闭合")
                val c = s[i]
                if (c == '"') { i++; return sb.toString() }
                if (c == '\\') {
                    i++
                    if (eof()) throw IllegalArgumentException("JSON 字符串未闭合")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) throw IllegalArgumentException("JSON \\u 转义不完整")
                            val hex = s.substring(i + 1, i + 5)
                            val code = hex.toIntOrNull(16) ?: throw IllegalArgumentException("JSON \\u 转义不正确")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("JSON 转义不正确: \\$e")
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isFloat = false
            if (i < s.length && s[i] == '.') {
                isFloat = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            if (!isFloat) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw IllegalArgumentException("JSON 数字不正确")
        }
    }
}
