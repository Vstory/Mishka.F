package top.yukonga.mishka.custom.forms

/*
 * 「字段整理」（整理配置字段顺序）——mihomo_box 模块 配置页 源码编辑器工具条上
 * `#tidyBtn`（title=整理配置字段顺序）的 Kotlin 版，逐函数对齐参考实现
 * webroot/ui/js/core.js 的 tidyMihomoConfig 及其辅助函数。
 *
 * 参考实现的语义（照抄，不做「改良」）：
 *   * 只改字段顺序，不动格式：多行块式 / 单行流式 / 注释 / 空行位置 / 锚点标记全部保持原样；
 *   * 顶层按 OFFICIAL_TOP_ORDER（59 项）排序，官方表里没有的键位次 999、彼此保持原有相对顺序；
 *   * 顶层「锚点定义块」（非官方顶层键 + 键行带 &锚点）整体排到最前，与其后的普通配置之间固定留一个空行；
 *   * 段内：dns / tun / sniffer / … 等 17 个已知键按 OFFICIAL_INNER_ORDER 排；
 *     proxies、proxy-groups 逐条目排序（proxy-groups 里 `<<` 贴身排在 name 正下方），
 *     proxy-providers、rule-providers 逐条目排序；流式 `{…}` 在同一行内重排；
 *   * 文件头注释（最后一个空行之前的注释）与文件尾注释各自保持位置；结尾换行习惯保持不变，底部不加空行。
 *
 * 实现约束：
 *   * 纯文本处理：只用 java.util 与 Kotlin 标准库，零 Android / YAML 依赖，
 *     与参考实现可以逐字节对拍（见 tools/tidy/check_tidy_parity.mjs）。
 *   * 正则语义对齐 JS：`^`/`$` 用 \A/\z（Java 的 `$` 会容忍末尾换行，JS 不会），
 *     空白类用 [\t\n\x0B\f\r\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]
 *     （Java 的 \s 不含 U+00A0 / U+FEFF，JS 的 \s 含），trim / isBlank 同理走 jsTrim。
 *
 * 对拍（tools/tidy/check_tidy_parity.sh）：把本文件 + YamlEngine.kt 用 kotlinc 编成 jar，
 * 与 tools/tidy/tidy-ref.mjs（从参考实现 core.js 原样抽出的函数）在同一批 cases 上逐字节比对。
 */

internal object ConfigTidy {

    // ---------------------------------------------------------------- 官方字段顺序（Mihomo / MetaCubeX）

    private val OFFICIAL_TOP_ORDER = listOf(
        // 1. General & Ports & Inbound Access
        "mixed-port", "port", "socks-port", "redir-port", "tproxy-port", "shadowsocks-port",
        "mode", "log-level", "ipv6", "allow-lan", "bind-address",
        "lan-allowed-ips", "lan-disallowed-ips", "authentication", "skip-auth-prefixes",
        "unified-delay", "tcp-concurrent", "interface-name", "routing-mark", "inbound-tproxy-mark",
        "external-controller", "external-ui", "secret", "external-controller-tls", "external-controller-cors",
        "external-ui-url", "external-ui-name",
        // 2. Geo & Models
        "geodata-mode", "geodata-loader", "geo-auto-update", "geo-update-interval", "geox-url", "geo-custom-url",
        "lgbm-auto-update", "lgbm-update-interval", "lgbm-url",
        // 3. Profile & Global
        "find-process-mode", "global-client-fingerprint", "global-ua",
        "keep-alive-idle", "keep-alive-interval", "disable-keep-alive", "profile",
        // 4. Sniffer
        "sniffer",
        // 5. Inbounds & Experimental
        "tun", "ebpf", "iptables", "listeners", "experimental",
        // 6. DNS & NTP
        "dns", "ntp",
        // 7. Hosts & TLS
        "hosts", "tls",
        // 8. Proxies, Groups & Providers
        "proxies", "proxy-groups", "proxy-providers",
        // 9. Rules & Providers
        "rule-providers", "sub-rules", "rules",
    )

    private val OFFICIAL_INNER_ORDER: Map<String, List<String>> = mapOf(
        "dns" to listOf(
            "enable", "prefer-h3", "listen", "ipv6", "use-hosts", "use-system-hosts",
            "respect-rules", "enhanced-mode", "fake-ip-range", "fake-ip-filter",
            "fake-ip-filter-mode", "default-nameserver", "nameserver", "fallback",
            "fallback-filter", "nameserver-policy", "proxy-server-nameserver",
            "direct-nameserver", "direct-nameserver-follow-policy",
        ),
        "fallback-filter" to listOf("geoip", "geoip-code", "geosite", "ipcidr", "domain"),
        "tun" to listOf(
            "enable", "stack", "device", "auto-route", "auto-redirect",
            "auto-detect-interface", "dns-hijack", "strict-route", "mtu", "gso",
            "gso-max-size", "endpoint-independent-nat", "udp-timeout", "file-descriptor",
        ),
        "sniffer" to listOf(
            "enable", "force-dns-mapping", "parse-pure-ip", "override-destination",
            "sniff", "force-domain", "skip-domain", "sniff-dns-mapping", "port-whitelist",
        ),
        "ebpf" to listOf("redirect-to-tun", "auto-redir", "local", "shared"),
        "ntp" to listOf("enable", "server", "port", "interval", "lookup-interface"),
        "experimental" to listOf("clash-core", "v2ray-api", "quic-go-disable-ecn", "quic-go-disable-gso"),
        "profile" to listOf("store-selected", "store-fake-ip"),
        "proxy-providers" to listOf(
            "type", "url", "interval", "proxy", "path", "header", "format",
            "health-check", "override", "filter", "exclude-filter", "exclude-type",
        ),
        "proxy-providers-item" to listOf(
            "type", "url", "interval", "proxy", "path", "header", "format",
            "health-check", "override", "filter", "exclude-filter", "exclude-type",
        ),
        "health-check" to listOf("enable", "url", "interval", "timeout", "lazy", "expected-status"),
        "proxy-groups" to listOf(
            "name", "type", "proxies", "use", "url", "interval", "timeout", "tolerance",
            "lazy", "expected-status", "max-failed-times", "hidden", "icon",
            "uselightgbm", "prefer-asn", "include-all-providers", "include-all-proxies", "empty-fallback",
            "filter", "exclude-filter", "exclude-type", "strategy", "disable-udp",
            "interface-name", "routing-mark",
        ),
        "proxy-groups-item" to listOf(
            "name", "type", "proxies", "use", "url", "interval", "timeout", "tolerance",
            "lazy", "expected-status", "max-failed-times", "hidden", "icon",
            "uselightgbm", "prefer-asn", "include-all-providers", "include-all-proxies", "empty-fallback",
            "filter", "exclude-filter", "exclude-type", "strategy", "disable-udp",
            "interface-name", "routing-mark",
        ),
        "rule-providers" to listOf("type", "behavior", "url", "path", "interval", "proxy", "header", "format"),
        "rule-providers-item" to listOf("type", "behavior", "url", "path", "interval", "proxy", "header", "format"),
        "proxies" to listOf(
            "name", "type", "server", "port", "password", "uuid", "cipher", "alterId",
            "udp", "tls", "skip-cert-verify", "servername", "network",
            "ws-opts", "grpc-opts", "h2-opts", "http-opts", "reality-opts",
        ),
        "proxies-item" to listOf(
            "name", "type", "server", "port", "password", "uuid", "cipher", "alterId",
            "udp", "tls", "skip-cert-verify", "servername", "network",
            "ws-opts", "grpc-opts", "h2-opts", "http-opts", "reality-opts",
        ),
    )

    // ---------------------------------------------------------------- 正则 / 空白

    /** JS 的 `\s`（Java 的 \s 少了 U+00A0 / U+FEFF，且二者都含 \x0B）。 */
    private const val JS_SPACE = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    private val EDGE_SPACE = Regex("^[$JS_SPACE]+|[$JS_SPACE]+\\z")

    private val KEY_LINE = Regex("\\A[$JS_SPACE]*(?:-[$JS_SPACE]+)?(?:(<<)|\"([^\"]+)\"|'([^']+)'|([^:#$JS_SPACE]+))[$JS_SPACE]*:")

    /** 段 / 条目行「值写成流式对象」：`: {…}` 可带行尾注释。 */
    private val FLOW_VALUE_TAIL = Regex(":[$JS_SPACE]*\\{.*\\}[$JS_SPACE]*(?:#.*)?\\z")

    private val FLOW_OBJECT_LINE =
        Regex("\\A([$JS_SPACE]*(?:[^:#\\r\\n]+:[$JS_SPACE]*)?(?:-[$JS_SPACE]+)?(?:&[^$JS_SPACE]+[$JS_SPACE]+)?)\\{(.*)\\}([$JS_SPACE]*(?:#.*)?)\\z")

    private val SEQ_ITEM_FLOW_LINE = Regex("\\A[$JS_SPACE]*-[$JS_SPACE]+(?:&[^$JS_SPACE]+[$JS_SPACE]+)?\\{.*\\}[$JS_SPACE]*(?:#.*)?\\z")

    private val DASH_PREFIX = Regex("\\A([$JS_SPACE]*-[$JS_SPACE]*(?:&[^$JS_SPACE]+[$JS_SPACE]*)?)(.*)\\z")

    private val FLOW_SEG_KEY = Regex("\\A[$JS_SPACE]*(<<|\"?([A-Za-z0-9_.\\-]+)\"?)[$JS_SPACE]*:")

    private val SEQ_START = Regex("\\A-[$JS_SPACE]*(?:.*)?\\z")

    private val ANCHOR_TOKEN = Regex("&[^$JS_SPACE]")

    private val FLOW_SEG_TAIL = Regex(":[$JS_SPACE]*\\{.*\\}\\z")

    /** 条目字段排序位次：官方顺序里有的按序，没有的靠后（999，彼此保持原相对顺序）。 */
    private const val MERGE_AFTER_NAME_RANK = 0.5

    // ---------------------------------------------------------------- 工具

    private fun leadingWidth(line: String): Int {
        var i = 0
        while (i < line.length && isJsSpace(line[i])) i++
        return i
    }

    private fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ' -> true
        '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    // ---------------------------------------------------------------- 入口

    fun tidy(text: String): String {
        // 行尾符按原文保留：参考实现只按 '\n' 切分、再按 '\n' 拼回，CRLF 文件会拆出与裸 '\n' 混排的
        // 换行（原行留着 '\r'、新加的空行是 LF）。Android 侧的编辑器与 YAML 引擎都是行尾符敏感的，
        // 这里统一先剥掉行尾的 '\r'，最后按原文的行尾符拼回去——配置内容与顺序完全一致，
        // 只是不产生混排换行（对拍脚本按「去掉 '\r' 后逐字节一致」比对，另断言 CRLF 仍为 CRLF）。
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val topBlocks = ArrayList<Block>()
        var fileHeader: List<String> = emptyList()
        val curComments = ArrayList<String>()
        var curKey: String? = null
        val curLines = ArrayList<String>()

        for (l in lines) {
            val trimmed = jsTrim(l)
            if (trimmed.isEmpty()) {
                if (curKey != null) curLines.add(l) else curComments.add(l)
                continue
            }

            val indent = leadingWidth(l)
            if (trimmed.startsWith("#")) {
                if (curKey != null && indent > 0) curLines.add(l) else curComments.add(l)
                continue
            }

            if (indent == 0) {
                val k = extractKeyFromLine(l)
                if (k != null) {
                    if (curKey != null) {
                        topBlocks.add(Block(curKey, trimLines(curLines)))
                        curLines.clear()
                        curLines.addAll(curComments)
                        curLines.add(l)
                        curComments.clear()
                    } else {
                        val split = splitLeadingComments(curComments)
                        fileHeader = split.first
                        curLines.clear()
                        curLines.addAll(split.second)
                        curLines.add(l)
                        curComments.clear()
                    }
                    curKey = k
                    continue
                }
            }

            if (curKey != null) curLines.add(l) else curComments.add(l)
        }

        if (curKey != null) {
            topBlocks.add(Block(curKey, trimLines(curLines)))
        }

        val fileFooter = trimLines(curComments)

        topBlocks.forEach { it.lines = reorderSectionLines(it.key, it.lines) }

        // 是否「锚点定义块」：顶层自定义键且键行带 &锚点（官方顶层键不算）
        topBlocks.forEach { b ->
            val keyLine = b.lines.firstOrNull { !jsTrim(it).startsWith("#") && extractKeyFromLine(it) == b.key }
                ?: b.lines.firstOrNull().orEmpty()
            b.isAnchor = ANCHOR_TOKEN.containsMatchIn(keyLine) && b.key !in OFFICIAL_TOP_ORDER
        }

        topBlocks.sortWith { a, b ->
            if (a.isAnchor && !b.isAnchor) -1
            else if (!a.isAnchor && b.isAnchor) 1
            else topRank(a.key) - topRank(b.key)
        }

        val out = ArrayList<String>()
        if (fileHeader.isNotEmpty()) {
            out.addAll(fileHeader)
            out.add("")
        }

        var prevWasMulti = false
        for (i in topBlocks.indices) {
            val b = topBlocks[i]
            val isMulti = b.lines.size > 1
            // 锚点区与其后的普通配置之间固定空一行：锚点常写成单行流式，没有注释时
            // 整块都是单行，不单独判断就会和下面的 mixed-port 等连成一片。
            val leavingAnchors = i > 0 && topBlocks[i - 1].isAnchor && !b.isAnchor
            if (i > 0 && (isMulti || prevWasMulti || leavingAnchors)) {
                out.add("")
            }
            out.addAll(b.lines)
            prevWasMulti = isMulti
        }

        if (fileFooter.isNotEmpty()) {
            out.add("")
            out.addAll(fileFooter)
        }

        // 保留原文件是否有结尾换行的习惯，不额外多加空行（用户要求：整理后底部不加空行）
        return out.joinToString(eol) + if (text.endsWith("\n")) eol else ""
    }

    // ---------------------------------------------------------------- 按钮流程

    /** 按钮按这个结果选提示语（与参考实现按钮里的四条 uiToast 一一对应）。 */
    internal sealed interface Outcome {

        /** 整理后与原文逐字节相同：字段顺序已经符合官方规范。 */
        data object Unchanged : Outcome

        /** 整理成功：[text] 就是要写回编辑器的文本。 */
        data class Tidied(val text: String) : Outcome

        /** 源码本身就是坏掉的 YAML：不动它，提示先修（对齐参考实现的 parseConfigText 前置检查）。 */
        data class SourceInvalid(val detail: String) : Outcome

        /** 回落校验没过（结果解析不通过 / 顶层字段集合变了）：不动原文，宁可保留原样。 */
        data class VerifyFailed(val detail: String) : Outcome
    }

    /**
     * 按钮的完整流程，对齐参考实现 tidyBtn 的 onclick：
     *   ① 源码先过一遍配置表单的 YAML 引擎——语法就坏掉时不整理（内核会拒绝的配置，
     *      整理它只会让用户更难定位错误）；浏览器的这一步是 js-yaml，这里用同一套自研引擎，
     *      能抓到括号不配对这类结构性错误；
     *   ② [tidy] 整理；
     *   ③ 与原文一致 → [Outcome.Unchanged]；
     *   ④ 结果仍要能解析，且顶层字段集合一字不差（只换顺序，不增不减）→ 否则 [Outcome.VerifyFailed]。
     */
    internal fun apply(source: String): Outcome {
        runCatching { YamlDoc.parse(source) }.getOrElse {
            return Outcome.SourceInvalid(it.message ?: it.javaClass.simpleName)
        }
        val tidied = runCatching { tidy(source) }.getOrElse {
            return Outcome.VerifyFailed(it.message ?: it.javaClass.simpleName)
        }
        if (tidied == source) return Outcome.Unchanged
        runCatching { YamlDoc.parse(tidied) }.getOrElse { e ->
            return Outcome.VerifyFailed(e.message ?: e.javaClass.simpleName)
        }
        val before = topLevelKeys(source).sorted()
        val after = topLevelKeys(tidied).sorted()
        if (before != after) {
            return Outcome.VerifyFailed("顶层字段集合发生变化（${before.size} → ${after.size}）")
        }
        return Outcome.Tidied(tidied)
    }

    /** 整理结果的顶层字段名（按文本口径），供调用方做「键集合不变」的回落校验。 */
    fun topLevelKeys(text: String): List<String> = text.split('\n').mapNotNull { extractKeyFromLineTop(it) }

    private fun extractKeyFromLineTop(line: String): String? {
        if (leadingWidth(line) != 0) return null
        if (jsTrim(line).startsWith("#")) return null
        return extractKeyFromLine(line)
    }

    // ---------------------------------------------------------------- 段 / 条目重排

    private class Block(val key: String, var lines: List<String>, var isAnchor: Boolean = false)

    private class Entry(val key: String, var lines: List<String>)

    private data class Split<T>(val entries: List<T>, val trailing: List<String>)

    private fun topRank(key: String): Int = OFFICIAL_TOP_ORDER.indexOf(key).let { if (it >= 0) it else 999 }

    private fun entryRankOf(key: String, order: List<String>?, mergeAfterName: Boolean = false): Double = when {
        key == "<<" -> if (mergeAfterName) MERGE_AFTER_NAME_RANK else -1.0
        order != null && order.indexOf(key) >= 0 -> order.indexOf(key).toDouble()
        else -> 999.0
    }

    private fun extractKeyFromLine(line: String): String? {
        val m = KEY_LINE.find(line) ?: return null
        for (i in 1..4) {
            val g = m.groups[i]?.value
            if (g != null) return g
        }
        return null
    }

    private fun trimLines(lines: List<String>): List<String> {
        var s = 0
        while (s < lines.size && jsTrim(lines[s]).isEmpty()) s++
        var e = lines.size - 1
        while (e >= s && jsTrim(lines[e]).isEmpty()) e--
        return lines.subList(s, e + 1).toList()
    }

    /** 单行流式对象行重建：`k: {…}` / `- {…}` 通用；段内键按 order 重排。 */
    private fun reorderFlowObject(flowStr: String, order: List<String>?, mergeAfterName: Boolean = false): String {
        val m = FLOW_OBJECT_LINE.matchEntire(flowStr) ?: return flowStr
        val prefix = m.groupValues[1]
        val inner = m.groupValues[2]
        val suffix = m.groupValues[3]

        val items = splitTopLevel(inner).map { seg ->
            val km = FLOW_SEG_KEY.find(seg)
            val key = when {
                km == null -> ""
                km.groupValues[1] == "<<" -> "<<"
                else -> stripEdgeQuotes(km.groupValues[2].ifEmpty { km.groupValues[1] })
            }
            val rank = entryRankOf(key, order, mergeAfterName)
            val formattedSeg =
                if (key.isNotEmpty() && OFFICIAL_INNER_ORDER.containsKey(key) && FLOW_SEG_TAIL.containsMatchIn(seg)) {
                    reorderFlowObject(seg, OFFICIAL_INNER_ORDER.getValue(key))
                } else seg
            RankedSeg(key, formattedSeg, rank)
        }.sortedBy { it.rank }

        val newInner = items.joinToString(", ") { it.seg }
        return prefix + "{" + (if (newInner.isNotEmpty()) " $newInner " else "") + "}" + suffix
    }

    private class RankedSeg(val key: String, val seg: String, val rank: Double)

    private fun splitMappingEntries(lines: List<String>, expectedIndent: Int): Split<Entry> {
        val entries = ArrayList<Entry>()
        val curComments = ArrayList<String>()
        var curKey: String? = null
        val curLines = ArrayList<String>()

        for (l in lines) {
            val trimmed = jsTrim(l)
            if (trimmed.isEmpty()) {
                if (curKey != null) curLines.add(l) else curComments.add(l)
                continue
            }

            val indent = leadingWidth(l)
            if (trimmed.startsWith("#")) {
                if (curKey != null && indent > expectedIndent) curLines.add(l) else curComments.add(l)
                continue
            }

            if (indent == expectedIndent) {
                val k = extractKeyFromLine(l)
                if (k != null) {
                    if (curKey != null) {
                        entries.add(Entry(curKey, trimLines(curLines)))
                        curLines.clear()
                    }
                    curKey = k
                    curLines.addAll(curComments)
                    curLines.add(l)
                    curComments.clear()
                    continue
                }
            }

            if (curKey != null) curLines.add(l) else curComments.add(l)
        }

        if (curKey != null) {
            entries.add(Entry(curKey, trimLines(curLines)))
        }

        return Split(entries, curComments.toList())
    }

    private fun reorderSeqItem(lines: List<String>, seqIndent: Int, order: List<String>, mergeAfterName: Boolean): List<String> {
        var dashLineIdx = -1
        for (i in lines.indices) {
            if (jsTrim(lines[i]).isNotEmpty() && !jsTrim(lines[i]).startsWith("#")) {
                dashLineIdx = i
                break
            }
        }
        if (dashLineIdx == -1) return lines

        val itemComments = lines.subList(0, dashLineIdx)
        val itemBodyLines = lines.subList(dashLineIdx, lines.size)

        val firstNonComment = itemBodyLines[0]
        if (SEQ_ITEM_FLOW_LINE.matchEntire(firstNonComment) != null) {
            return itemComments + itemBodyLines.map { l ->
                if (SEQ_ITEM_FLOW_LINE.matchEntire(l) != null) reorderFlowObject(l, order, mergeAfterName) else l
            }
        }

        val dashMatch = DASH_PREFIX.matchEntire(firstNonComment) ?: return lines

        val dashPrefix = dashMatch.groupValues[1]
        val dashRest = jsTrim(dashMatch.groupValues[2])

        if (dashRest.isEmpty()) {
            val headerLine = firstNonComment
            val bodyLines = itemBodyLines.subList(1, itemBodyLines.size)
            val firstField = bodyLines.firstOrNull { jsTrim(it).isNotEmpty() && !jsTrim(it).startsWith("#") } ?: return lines
            val fieldIndent = leadingWidth(firstField)
            val (entries0, trailing0) = splitMappingEntries(bodyLines, fieldIndent)
            val entries = entries0.sortedBy { entryRankOf(it.key, order, mergeAfterName) }
            val newBody = entries.flatMap { it.lines } + trailing0
            return itemComments + listOf(headerLine) + newBody
        }

        var spacesIndent = dashPrefix.replace("-", " ").length
        if (itemBodyLines.size > 1) {
            val secondLine = itemBodyLines.subList(1, itemBodyLines.size)
                .firstOrNull { jsTrim(it).isNotEmpty() && !jsTrim(it).startsWith("#") }
            if (secondLine != null) {
                spacesIndent = leadingWidth(secondLine)
            }
        }
        val spacesPrefix = " ".repeat(spacesIndent)

        val convertedLines = listOf(spacesPrefix + dashRest) + itemBodyLines.subList(1, itemBodyLines.size)
        val (entries0, trailing) = splitMappingEntries(convertedLines, spacesIndent)
        if (entries0.isEmpty()) return lines

        val entries = entries0.sortedBy { entryRankOf(it.key, order, mergeAfterName) }

        val result = ArrayList<String>()
        var isFirstEntry = true
        for (entry in entries) {
            if (isFirstEntry) {
                var keyLineFound = false
                for (l in entry.lines) {
                    if (!keyLineFound && l.startsWith(spacesPrefix)) {
                        result.add(dashPrefix + l.substring(spacesPrefix.length))
                        keyLineFound = true
                    } else {
                        result.add(l)
                    }
                }
                isFirstEntry = false
            } else {
                result.addAll(entry.lines)
            }
        }
        result.addAll(trailing)
        return itemComments + result
    }

    private fun splitSequenceItems(lines: List<String>, seqIndent: Int): Split<List<String>> {
        val items = ArrayList<List<String>>()
        val curComments = ArrayList<String>()
        var curItemLines: ArrayList<String>? = null

        for (l in lines) {
            val trimmed = jsTrim(l)
            if (trimmed.isEmpty()) {
                if (curItemLines != null) curItemLines.add(l) else curComments.add(l)
                continue
            }

            val indent = leadingWidth(l)
            if (trimmed.startsWith("#")) {
                if (curItemLines != null && indent > seqIndent) curItemLines.add(l) else curComments.add(l)
                continue
            }

            val isSeqStart = indent == seqIndent && SEQ_START.matchEntire(l.substring(indent)) != null
            if (isSeqStart) {
                if (curItemLines != null) {
                    items.add(trimLines(curItemLines))
                }
                curItemLines = ArrayList()
                curItemLines.addAll(curComments)
                curItemLines.add(l)
                curComments.clear()
                continue
            }

            if (curItemLines != null) curItemLines.add(l) else curComments.add(l)
        }

        if (curItemLines != null) {
            items.add(trimLines(curItemLines))
        }

        return Split(items, curComments.toList())
    }

    private fun reorderMappingBlock(lines: List<String>, expectedIndent: Int, order: List<String>): List<String> {
        val (entries0, trailing) = splitMappingEntries(lines, expectedIndent)
        if (entries0.isEmpty()) return lines

        // 映射型条目（规则集/代理集等）：`<<` 维持置顶（仅 proxy-groups 序列条目排到 name 下方）
        val entries = entries0.sortedBy { entryRankOf(it.key, order) }

        entries.forEach { entry ->
            val subOrder = OFFICIAL_INNER_ORDER[entry.key] ?: return@forEach
            var klIdx = -1
            for (i in entry.lines.indices) {
                if (!jsTrim(entry.lines[i]).startsWith("#") && extractKeyFromLine(entry.lines[i]) == entry.key) {
                    klIdx = i
                    break
                }
            }
            if (klIdx >= 0) {
                val kl = entry.lines[klIdx]
                if (FLOW_VALUE_TAIL.containsMatchIn(kl)) {
                    entry.lines = entry.lines.toMutableList().also { it[klIdx] = reorderFlowObject(kl, subOrder) }
                } else {
                    val subLines = entry.lines.subList(klIdx + 1, entry.lines.size)
                    val firstSub = subLines.firstOrNull { jsTrim(it).isNotEmpty() && !jsTrim(it).startsWith("#") }
                    if (firstSub != null) {
                        val subIndent = leadingWidth(firstSub)
                        if (subIndent > expectedIndent) {
                            val reorderedSub = reorderMappingBlock(subLines, subIndent, subOrder)
                            entry.lines = entry.lines.subList(0, klIdx + 1) + reorderedSub
                        }
                    }
                }
            }
        }

        return entries.flatMap { it.lines } + trailing
    }

    private fun reorderSectionLines(key: String, lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        var klIdx = -1
        for (i in lines.indices) {
            if (!jsTrim(lines[i]).startsWith("#") && extractKeyFromLine(lines[i]) == key) {
                klIdx = i
                break
            }
        }
        if (klIdx == -1) return lines

        val headerLines = lines.subList(0, klIdx + 1).toMutableList()
        val keyLine = lines[klIdx]

        if (FLOW_VALUE_TAIL.containsMatchIn(keyLine) && OFFICIAL_INNER_ORDER.containsKey(key)) {
            headerLines[headerLines.size - 1] = reorderFlowObject(keyLine, OFFICIAL_INNER_ORDER.getValue(key))
            return headerLines + lines.subList(klIdx + 1, lines.size)
        }

        val bodyLines = lines.subList(klIdx + 1, lines.size)
        if (bodyLines.isEmpty()) return lines

        val firstContent = bodyLines.firstOrNull { jsTrim(it).isNotEmpty() && !jsTrim(it).startsWith("#") } ?: return lines
        val childIndent = leadingWidth(firstContent)

        if (key == "proxy-groups" || key == "proxies") {
            val itemOrder = if (key == "proxy-groups") OFFICIAL_INNER_ORDER.getValue("proxy-groups-item")
            else OFFICIAL_INNER_ORDER.getValue("proxies-item")
            val (items, trailing) = splitSequenceItems(bodyLines, childIndent)
            // 仅代理组：条目内 `<<: *锚点` 排在 name 正下方；proxies 等其他段维持置顶
            val mergeAfterName = key == "proxy-groups"
            val reorderedItems = items.map { reorderSeqItem(it, childIndent, itemOrder, mergeAfterName) }
            return headerLines + reorderedItems.flatten() + trailing
        }

        if (key == "proxy-providers" || key == "rule-providers") {
            val itemOrder = if (key == "proxy-providers") OFFICIAL_INNER_ORDER.getValue("proxy-providers-item")
            else OFFICIAL_INNER_ORDER.getValue("rule-providers-item")
            val (entries0, trailing) = splitMappingEntries(bodyLines, childIndent)
            entries0.forEach { entry ->
                var eKlIdx = -1
                for (i in entry.lines.indices) {
                    if (!jsTrim(entry.lines[i]).startsWith("#") && extractKeyFromLine(entry.lines[i]) == entry.key) {
                        eKlIdx = i
                        break
                    }
                }
                if (eKlIdx >= 0) {
                    val kl = entry.lines[eKlIdx]
                    if (FLOW_VALUE_TAIL.containsMatchIn(kl)) {
                        entry.lines = entry.lines.toMutableList().also { it[eKlIdx] = reorderFlowObject(kl, itemOrder) }
                    } else {
                        val subLines = entry.lines.subList(eKlIdx + 1, entry.lines.size)
                        val firstSub = subLines.firstOrNull { jsTrim(it).isNotEmpty() && !jsTrim(it).startsWith("#") }
                        if (firstSub != null) {
                            val subIndent = leadingWidth(firstSub)
                            val reorderedSub = reorderMappingBlock(subLines, subIndent, itemOrder)
                            entry.lines = entry.lines.subList(0, eKlIdx + 1) + reorderedSub
                        }
                    }
                }
            }
            return headerLines + entries0.flatMap { it.lines } + trailing
        }

        if (OFFICIAL_INNER_ORDER.containsKey(key)) {
            val reorderedBody = reorderMappingBlock(bodyLines, childIndent, OFFICIAL_INNER_ORDER.getValue(key))
            return headerLines + reorderedBody
        }

        return lines
    }

    private fun splitLeadingComments(rawComments: List<String>): Pair<List<String>, List<String>> {
        val comments = trimLines(rawComments)
        if (comments.isEmpty()) return emptyList<String>() to emptyList()

        var splitIdx = -1
        for (i in comments.indices) {
            if (jsTrim(comments[i]).isEmpty()) splitIdx = i
        }
        if (splitIdx >= 0) {
            val header = trimLines(comments.subList(0, splitIdx))
            val keyComments = trimLines(comments.subList(splitIdx + 1, comments.size))
            if (header.isNotEmpty() && keyComments.isNotEmpty()) {
                return header to keyComments
            }
        }
        return emptyList<String>() to comments
    }

    private fun splitTopLevel(body: String): List<String> {
        val segs = ArrayList<String>()
        var depth = 0
        var q: Char? = null
        val cur = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (q != null) {
                cur.append(c)
                if (c == q) {
                    if (i + 1 < body.length && body[i + 1] == q) {
                        cur.append(body[i + 1])
                        i++
                    } else {
                        q = null
                    }
                }
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                q = c
                cur.append(c)
                i++
                continue
            }
            if (c == '{' || c == '[') depth++
            if (c == '}' || c == ']') depth--
            if (c == ',' && depth == 0) {
                segs.add(cur.toString())
                cur.setLength(0)
                i++
                continue
            }
            cur.append(c)
            i++
        }
        if (jsTrim(cur.toString()).isNotEmpty()) segs.add(cur.toString())
        return segs.map { jsTrim(it) }.filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- JS 语义的小工具

    private fun jsTrim(s: String): String = s.replace(EDGE_SPACE, "")

    /** JS `s.replace(/^"|"$/g, '')`：两端各自独立去一个双引号。 */
    private fun stripEdgeQuotes(s: String): String = s.removePrefix("\"").removeSuffix("\"")
}
