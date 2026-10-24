package top.yukonga.mishka.custom.anchor

import androidx.compose.runtime.Immutable

/**
 * 锚点的**行级手术**：所有操作都是「原文进、原文出」，只重写目标块/目标行的字符范围，
 * 其余行（含 CRLF、空行、行尾空格、注释）一个字节都不动——这是与 mihomo_box 相同的约定，
 * 也是「改动后仍能跟上游订阅配置对上」的前提。
 *
 * 手术结果不做完整 YAML 解析（Kotlin 侧没有 YAML 依赖）：面板在每次改动后立刻重扫锚点图，
 * 悬空别名、重复定义当场可见；真正的合法性由保存路径上的内核校验兜底（非法 YAML 会阻止保存，
 * 文件不会被写坏）。见 FileManagerEditorScreen 的 saveWithValidation。
 */

@Immutable
data class DefLineShape(
    val indent: Int,
    val dash: Boolean,
    val key: String,
    val keyRaw: String,
    val name: String,
    val value: String,
)

/** 引用行形态。[key] 为 null 表示 `<<: *x` 合并继承行。 */
@Immutable
data class RefLineShape(
    val indent: String,
    val key: String?,
    val comment: String,
)

sealed interface DropAnchorResult {
    /** 定义所在的块是 mihomo 真正会读的配置段（mixed-port / dns / proxies …）：只摘 `&名`，块留给内核。 */
    data class KeptBlock(val text: String) : DropAnchorResult

    /** 为挂 `&名` 而存在的自建容器：整块删干净，不留 `键: 值` 残块。 */
    data class RemovedBlock(val text: String, val removedLines: Int) : DropAnchorResult

    data object NotFound : DropAnchorResult

    /** 同一名字有多处 `&定义`，无法确定删哪个。 */
    data class Ambiguous(val count: Int) : DropAnchorResult
}

object AnchorEdit {

    // 名字字符集与参考实现一致：解析阶段不卡合法性（&9bad 这类名字在 YAML 里合法、
    // mihomo 也可能照读），合法性只在「新建/改名」时用 AnchorScan.isValidName 校验。
    private val NamePat = "[^\\s,\\[\\]{}#]+"
    private val DefLineRegex = Regex("^(\\s*)(-\\s+)?([^:#]+?)\\s*:\\s*&($NamePat)\\s*(.*)$")
    private val MergeRefRegex = Regex("^(\\s*)<<\\s*:\\s*\\*($NamePat)\\s*(#.*)?$")
    private val ValueRefRegex = Regex("^(\\s*)([^:\\s#][^:]*?)\\s*:\\s*\\*($NamePat)\\s*(#.*)?$")

    /**
     * mihomo 内核真正会读的顶层配置段（与 mihomo_box 的 `MIHOMO_TOP_KEYS` 同表）。
     * 删锚点时这些块只摘 `&名`、保留块体；其余顶层键都是「为挂 `&名` 而存在」的自建容器，
     * 连块一起删——否则会在配置里留下 `代理合集: {…}` 这种内核不认又删不掉的残块。
     */
    val MIHOMO_TOP_KEYS: Set<String> = setOf(
        "port", "socks-port", "redir-port", "tproxy-port", "mixed-port", "bind-address",
        "mode", "log-level", "ipv6", "allow-lan", "skip-auth-prefixes", "authentication",
        "unified-delay", "tcp-concurrent", "interface-name", "routing-mark", "find-process-mode",
        "global-client-fingerprint", "keep-alive-idle", "keep-alive-interval",
        "external-controller", "external-controller-cors", "external-controller-pipe",
        "external-controller-unix", "external-doh-server", "external-ui", "external-ui-url",
        "external-ui-name", "secret", "geox-url", "geo-auto-update", "geo-update-interval",
        "geodata-mode", "geodata-loader", "geo-auto-private-network",
        "tun", "ebpf", "dns", "sniffer", "hosts", "ntp", "profile", "script", "experimental", "tls",
        "iptables", "listeners", "proxies", "proxy-groups", "rules", "sub-rules",
        "rule-providers", "proxy-providers", "clash-for-android", "auto-redirect",
    )

    // ==================== 行形态解析 ====================

    fun parseDefLine(line: String): DefLineShape? {
        val m = DefLineRegex.find(line) ?: return null
        return DefLineShape(
            indent = m.groupValues[1].length,
            dash = m.groupValues[2].isNotEmpty(),
            key = unquote(m.groupValues[3].trim()),
            keyRaw = m.groupValues[3].trim(),
            name = m.groupValues[4],
            value = m.groupValues[5].trim(),
        )
    }

    /** 引用行形态：`<<: *x` 或 `键: *x`（整行只引用一个锚点）。形态复杂时返回 null。 */
    fun parseRefLine(line: String): RefLineShape? {
        MergeRefRegex.find(line)?.let { m ->
            return RefLineShape(m.groupValues[1], null, m.groupValues[3].trimEnd())
        }
        ValueRefRegex.find(line)?.let { m ->
            return RefLineShape(m.groupValues[1], m.groupValues[2].trim(), m.groupValues[4].trimEnd())
        }
        return null
    }

    /** 该行引用的锚点名（裸文本，可能多个；形态复杂时返回空表）。 */
    fun refNamesOnLine(line: String): List<String> = AnchorScan.tokensOnLine(line).refs

    // ==================== 手术 ====================

    /** 级联改名：所有裸文本里的 `&旧` / `*旧` 一起改（引号内与注释后的同名文本不动）。 */
    fun renameAnchor(text: String, from: String, to: String): String {
        if (from == to) return text
        return text.split('\n').joinToString("\n") { replaceTokenInLine(it, from, to) }
    }

    /** 只改定义行的顶层键（引用不动）。 */
    fun renameDefKey(text: String, defLine1: Int, newKeyRaw: String): String? {
        val lines = text.split('\n').toMutableList()
        val idx = defLine1 - 1
        val shape = lines.getOrNull(idx)?.let { parseDefLine(it) } ?: return null
        val comment = AnchorScan.trailingComment(lines[idx])
        val value = AnchorScan.withoutComment(shape.value).trimEnd() // 注释已单独取出，别拼两遍
        val sb = StringBuilder()
        sb.append(" ".repeat(shape.indent))
        if (shape.dash) sb.append("- ")
        sb.append(newKeyRaw).append(": &").append(shape.name)
        if (value.isNotEmpty()) sb.append(' ').append(value)
        if (comment.isNotEmpty()) sb.append(' ').append(comment)
        lines[idx] = sb.toString()
        return lines.joinToString("\n")
    }

    /** 删除整行（清除 `<<: *x` 继承、删除悬空引用行）。 */
    fun deleteLine(text: String, line1: Int): String {
        val lines = text.split('\n').toMutableList()
        if (line1 - 1 !in lines.indices) return text
        lines.removeAt(line1 - 1)
        return lines.joinToString("\n")
    }

    /** 改绑引用行：[newName] 为 null 时等于删除该行（`<<:` 行的「不继承」）。 */
    fun rebindRefLine(text: String, line1: Int, newName: String?): String? {
        val lines = text.split('\n').toMutableList()
        val idx = line1 - 1
        val raw = lines.getOrNull(idx) ?: return null
        val shape = parseRefLine(raw) ?: return null
        if (newName == null) {
            if (shape.key != null) return null // 值引用没有「清除」语义：要么换绑，要么删掉整个键
            lines.removeAt(idx)
            return lines.joinToString("\n")
        }
        val comment = if (shape.comment.isEmpty()) "" else " " + shape.comment
        lines[idx] = if (shape.key == null) {
            shape.indent + "<<: *" + newName + comment
        } else {
            shape.indent + shape.key + ": *" + newName + comment
        }
        return lines.joinToString("\n")
    }

    /**
     * 删除锚点定义。内核配置段只摘 `&名`（块是配置本身，不能删）；自建容器整块删。
     * 有多处定义时拒绝（无法确定删哪个），由面板提示用户去源码处理。
     */
    fun dropAnchor(text: String, name: String): DropAnchorResult {
        val lines = text.split('\n')
        val defIndexes = lines.indices.filter { AnchorScan.tokensOnLine(lines[it]).defs.contains(name) }
        if (defIndexes.isEmpty()) return DropAnchorResult.NotFound
        if (defIndexes.size > 1) return DropAnchorResult.Ambiguous(defIndexes.size)
        val index = defIndexes.first()
        // 只有「缩进 0 的 键: &名」才算顶层定义块；解析不出顶层键（流式根、序列项…）
        // 一律只摘 &名，不猜块边界。
        val topKey = parseDefLine(lines[index])?.takeIf { it.indent == 0 && !it.dash }?.key
        if (topKey == null || topKey in MIHOMO_TOP_KEYS) {
            val out = lines.toMutableList()
            out[index] = stripAnchorToken(lines[index], name)
            return DropAnchorResult.KeptBlock(out.joinToString("\n"))
        }
        // 自建容器（为挂 &名 而建的顶层条目）整块删。空行处理与 mihomo_box 一致：
        // 删的是文件里第一块有内容的定义 → 连块后的空行一起收（文件头不留空白）；
        // 否则块尾空行留在原地当段落间距，只收掉块前多出来的那一行空行。
        var sectionStart = lines.size
        for (i in index + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (!line[0].isWhitespace()) {
                sectionStart = i
                break
            }
        }
        val firstContent = index == 0 || lines.subList(0, index).all { it.isBlank() }
        var cut = sectionStart
        if (firstContent) {
            while (cut < lines.size && lines[cut].isBlank()) cut++
        } else {
            while (cut > index + 1 && lines[cut - 1].isBlank()) cut--
        }
        val out = lines.toMutableList()
        repeat(cut - index) { out.removeAt(index) }
        if (firstContent) {
            while (out.isNotEmpty() && out[0].isBlank()) out.removeAt(0)
        } else if (index > 0 && out[index - 1].isBlank() && (index >= out.size || out[index].isBlank())) {
            out.removeAt(index - 1)
        }
        // 删行数按**实际**差值报（含收掉的空行），别用 cut-index：折叠空行时那个数会偏小
        return DropAnchorResult.RemovedBlock(out.joinToString("\n"), lines.size - out.size)
    }

    /** 用 [replacement] 替换 [startLine1]..[endLine1] 整块（行级替换，其余原文不动）。 */
    fun replaceLines(text: String, startLine1: Int, endLine1: Int, replacement: String): String {
        val lines = text.split('\n')
        val start = (startLine1 - 1).coerceIn(0, lines.size)
        val end = (endLine1 - 1).coerceIn(0, lines.size - 1)
        if (end < start) return text
        val out = ArrayList<String>(lines.size + 4)
        out.addAll(lines.subList(0, start))
        if (replacement.isNotEmpty()) out.addAll(replacement.split('\n'))
        if (end + 1 <= lines.lastIndex) out.addAll(lines.subList(end + 1, lines.size))
        return out.joinToString("\n")
    }

    /** 新块插到文件头（mihomo_box 的 `newtop` 同行为：定义块集中放文件开头，便于各处 `<<:` 引用）。 */
    fun insertTopBlock(text: String, block: String): String {
        val body = block.trim('\n')
        if (body.isEmpty()) return text
        return if (text.isBlank()) "$body\n" else "$body\n$text"
    }

    /** 顶层键是否已被占用（含刚录入、尚未保存的手工编辑）。 */
    fun hasTopLevelKey(text: String, key: String): Boolean {
        val regex = Regex("^" + Regex.escape(key) + "\\s*:(\\s|$|#)")
        return text.split('\n').any { regex.containsMatchIn(it) }
    }

    /** 现有顶层键清单（缩进 0 的 `键:` 行），用于新建/改名时的重名检查。 */
    fun topLevelKeys(text: String): List<String> {
        val regex = Regex("^([^\\s:#][^:]*?)\\s*:")
        return text.split('\n').mapNotNull { line ->
            if (line.isEmpty() || line[0] == ' ' || line[0] == '\t' || line[0] == '#') return@mapNotNull null
            regex.find(line)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
        }.distinct()
    }

    // ==================== 内部工具 ====================

    /** 把裸文本里的 `&name` / `*name` 换成同名新锚点。 */
    private fun replaceTokenInLine(line: String, name: String, to: String): String {
        if (line.indexOf('&') < 0 && line.indexOf('*') < 0) return line
        val mask = AnchorScan.bareMask(line)
        val sb = StringBuilder(line.length + 8)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if ((c == '&' || c == '*') && mask[i]) {
                val end = i + 1 + name.length
                if (end <= line.length && line.regionMatches(i + 1, name, 0, name.length) && isTokenEnd(line, end)) {
                    sb.append(c).append(to)
                    i = end
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 锚点 token 的结束边界，与 mihomo_box 的 `(?=$|[\s,[\]{}#])` 一致。 */
    private fun isTokenEnd(line: String, index: Int): Boolean {
        if (index >= line.length) return true
        return when (val c = line[index]) {
            ' ', '\t', ',', '[', ']', '{', '}', '#' -> true
            else -> c.isWhitespace()
        }
    }

    /**
     * 摘掉定义行上的 `&名`，顺带修好留下的空格：`键: &名 值` → `键: 值`、`键: &名` → `键:`。
     * 序列项（`- &名 {…}`）同样处理。
     */
    private fun stripAnchorToken(line: String, name: String): String {
        val mask = AnchorScan.bareMask(line)
        var tokenStart = -1
        for (i in line.indices) {
            if (line[i] != '&' || !mask[i]) continue
            val end = i + 1 + name.length
            if (end <= line.length && line.regionMatches(i + 1, name, 0, name.length) && isTokenEnd(line, end)) {
                tokenStart = i
                val head = line.substring(0, i)
                var tail = line.substring(end)
                if (head.trimEnd().endsWith(":")) {
                    // 锚点后面原本就是值（或注释），补一个空格把它接回键上
                    val rest = tail.trimStart(' ')
                    return if (rest.isEmpty()) head.trimEnd() else head.trimEnd() + " " + rest
                }
                tail = tail.trimStart(' ')
                return if (tail.isEmpty()) head.trimEnd() else head + tail
            }
        }
        return if (tokenStart < 0) line else line
    }

    private fun unquote(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'").trim()
}

/**
 * 单行 YAML 值的规范化与自检。参数框里的值按「标量或行内 flow」处理：
 * 行尾注释剥掉（与 mihomo_box 的 js-yaml 往返同理），形态明显写坏的直接拒绝并让 UI 报错，
 * 不把半截值写进配置。
 */
object YamlValue {

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("&")) return null // 值里的锚点定义请用「文本」模式写
        if (trimmed.startsWith("|") || trimmed.startsWith(">")) return null // 块标量不是单行值
        val body = stripComment(trimmed).trim()
        if (body.isEmpty()) return ""
        if (!balanced(body)) return null
        val flow = body.startsWith("{") || body.startsWith("[")
        if (!flow) {
            // 裸文本里的 `: `（或结尾 `:`）会被 YAML 读成嵌套键，必须让用户加引号或改成 flow。
            // 引号里的 `: ` 不算——`"a: b"` 是合法单行标量。
            if (bareKeyColonAt(body) >= 0) return null
            if (body.startsWith("!") || body.startsWith("%") || body.startsWith("&")) return null
        }
        return body
    }

    /** 只在「行首或空白之后的 `#`」处切注释——YAML 的注释规则，URL 里的 `#frag` 不该被切掉。 */
    private fun stripComment(text: String): String {
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) {
                    if (i + 1 < text.length && text[i + 1] == quote) {
                        i += 2
                        continue
                    }
                    quote = null
                }
            } else when (c) {
                '"', '\'' -> quote = c
                '#' -> if (i == 0 || text[i - 1].isWhitespace()) return text.substring(0, i)
            }
            i++
        }
        return text
    }

    /**
     * 裸文本里第一个「会被读成键值分隔」的 `:` 的下标（0 基），没有则 -1。
     * 判定：不在引号内，且 `:` 后面是空白或到行尾——`"a: b"` / `http://x` / `1:30` 都不算。
     */
    private fun bareKeyColonAt(text: String): Int {
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) {
                    if (i + 1 < text.length && text[i + 1] == quote) {
                        i += 2
                        continue
                    }
                    quote = null
                }
            } else when (c) {
                '"', '\'' -> quote = c
                ':' -> if (i == text.lastIndex || text[i + 1].isWhitespace()) return i
            }
            i++
        }
        return -1
    }

    /** 引号与 `{}` / `[]` 平衡。圆括号不检查：mihomo 的规则与正则里大量出现 `(` `)`。 */
    private fun balanced(text: String): Boolean {
        var quote: Char? = null
        var square = 0
        var curly = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) {
                    if (i + 1 < text.length && text[i + 1] == quote) {
                        i += 2
                        continue
                    }
                    quote = null
                }
            } else when (c) {
                '"', '\'' -> quote = c
                '[' -> square++
                ']' -> {
                    square--
                    if (square < 0) return false
                }

                '{' -> curly++
                '}' -> {
                    curly--
                    if (curly < 0) return false
                }
            }
            i++
        }
        return quote == null && square == 0 && curly == 0
    }

    /** 键名校验：不能含 YAML 特殊字符，不能为空。 */
    fun normalizeKey(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty()) return null
        if (key.any { it == ':' || it == '#' || it == '&' || it == '*' || it == '!' || it == '|' || it == '>' }) return null
        if (key.first().isWhitespace() || key.last().isWhitespace()) return null
        return key
    }

    /** 顶层条目名的额外限制（mihomo_box 同规则：不能含空格与 YAML 特殊符号）。 */
    fun normalizeTopKey(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty()) return null
        if (key.any { it.isWhitespace() || it in "#:&*!|>%@`" }) return null
        return key
    }
}
