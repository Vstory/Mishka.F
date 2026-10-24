package top.yukonga.mishka.custom.forms

/**
 * 路由规则 / 流量隧道的「一行字符串」语法（纯 Kotlin，无 Compose 依赖，kotlinc 可直接编译对拍）。
 *
 * 规则串的拆合移植自参考实现 `pages-flow.js` 的 `parseRule` / `buildLine`：
 *  - 先摘掉末尾的 `,no-resolve`；第一段是类型（大写后在 [RULE_TYPES] 里的才规范化）；
 *  - `MATCH`：第 2 段起整段是目标；
 *  - 其它：**最后一段**是目标策略，类型与目标之间整段是匹配值（正则 / 逻辑规则里本来就有逗号）；
 *  - 只有一段：类型 + 空匹配值 + 空目标。
 * 与参考实现的唯一差别：末尾的 `,src`（内核的来源匹配参数）也当参数摘下来并原样写回，不会被当成目标策略改坏。
 */
object RuleText {

    /** flags 只会是 no-resolve / src（按原顺序）。 */
    class Parts(val type: String, val value: String, val policy: String, val flags: List<String>) {
        val noResolve: Boolean get() = flags.any { it.equals("no-resolve", ignoreCase = true) }
    }

    private val typeValues: Set<String> by lazy { RULE_TYPES.map { it.value }.toSet() }
    private val FLAG_RE = Regex("^(no-resolve|src)$", RegexOption.IGNORE_CASE)

    fun spec(type: String): RuleTypeSpec? = RULE_TYPES.firstOrNull { it.value == type }

    fun parse(raw: String): Parts {
        var s = raw.trim()
        val flags = ArrayList<String>()
        while (true) {
            val comma = s.lastIndexOf(',')
            if (comma < 0) break
            val tail = s.substring(comma + 1).trim()
            if (!FLAG_RE.matches(tail)) break
            flags.add(0, tail.lowercase())
            s = s.substring(0, comma)
        }
        val firstComma = s.indexOf(',')
        if (firstComma == -1) {
            val rt = s.trim().uppercase()
            return Parts(if (rt in typeValues) rt else s.trim().ifEmpty { "DOMAIN-SUFFIX" }, "", "", flags)
        }
        val rawType = s.substring(0, firstComma).trim().uppercase()
        val type = if (rawType in typeValues) rawType else s.substring(0, firstComma).trim()
        val rest = s.substring(firstComma + 1)
        if (type == "MATCH") return Parts("MATCH", "", rest.trim(), emptyList())
        val lastComma = rest.lastIndexOf(',')
        if (lastComma == -1) return Parts(type.ifEmpty { "DOMAIN-SUFFIX" }, rest.trim(), "", flags)
        return Parts(type.ifEmpty { "DOMAIN-SUFFIX" }, rest.substring(0, lastComma).trim(), rest.substring(lastComma + 1).trim(), flags)
    }

    /** 参考实现 buildLine：`类型[,匹配值],策略[,no-resolve]`；MATCH 不带匹配值也不带参数。 */
    fun format(type: String, value: String, policy: String, noResolve: Boolean, keepFlags: List<String> = emptyList()): String {
        val sb = StringBuilder(type)
        if (type != "MATCH") sb.append(',').append(value)
        if (policy.isNotEmpty()) sb.append(',').append(policy)
        if (type != "MATCH") {
            if (noResolve) sb.append(",no-resolve")
            for (f in keepFlags) if (f != "no-resolve") sb.append(',').append(f)
        }
        return sb.toString()
    }

    /** 列表行的标题 / 摘要。 */
    fun title(p: Parts): String = if (p.value.isEmpty()) p.type else "${p.type}  ${p.value}"
    fun summary(p: Parts): String = buildString {
        append("→ ").append(p.policy.ifEmpty { "（无目标）" })
        if (p.flags.isNotEmpty()) append(" · ").append(p.flags.joinToString(" "))
    }
}

/**
 * 流量隧道的字符串写法 `tcp/udp,地址:端口,目标:端口[,代理]`（内核 `config.go` 的 `tunnel.UnmarshalText`：
 * 逗号分 3 或 4 段，第 1 段按 `/` 拆成多个网络）。映射写法 `{network: [tcp], address, target, proxy}` 由表单直接编辑。
 */
object TunnelText {

    class Parts(val network: List<String>, val address: String, val target: String, val proxy: String?)

    fun parse(raw: String): Parts? {
        val p = raw.split(',').map { it.trim() }
        if (p.size != 3 && p.size != 4) return null
        val nets = p[0].split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (nets.isEmpty()) return null
        return Parts(nets, p[1], p[2], p.getOrNull(3)?.ifEmpty { null })
    }

    /** 参考实现的「单行写法」输入校验：至少 3 段（协议,监听,目标[,代理]）。 */
    fun validateLine(raw: String): String? {
        val parts = raw.split(',').map { it.trim() }
        if (parts.size < 3 || parts.any { it.isEmpty() }) return "格式：协议,监听地址,目标地址[,代理]（如 tcp/udp,127.0.0.1:6553,8.8.8.8:53）"
        if (parts.size > 4) return "最多 4 段：协议,监听地址,目标地址,代理"
        return null
    }

    fun toMap(p: Parts): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["network"] = p.network
        m["address"] = p.address
        m["target"] = p.target
        if (p.proxy != null) m["proxy"] = p.proxy
        return m
    }

    fun format(p: Parts): String =
        p.network.joinToString("/") + "," + p.address + "," + p.target + (p.proxy?.let { ",$it" } ?: "")

    fun summary(p: Parts): String =
        "${p.network.joinToString("/")} · ${p.address} → ${p.target}" + (p.proxy?.let { " · 经 $it" } ?: " · 直连")
}

/** 新建项起名：`base`、`base 2`、`base 3`… 直到不和现有名字冲突。 */
fun uniqueName(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var i = 2
    while ("$base $i" in existing) i++
    return "$base $i"
}

/** 参考实现 safeFileStem：集合名去掉路径分隔与空白等不安全字符，当默认文件名。 */
fun safeFileStem(name: String, fallback: String): String =
    (name.trim().ifEmpty { fallback }).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
