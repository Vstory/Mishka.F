package top.yukonga.mishka.custom.forms

// 配置表单用的 YAML 引擎（Kotlin 侧实现）。
//
// 与 tools/forms/forms_model.py 一一对应——那边是规范 + 性质测试（PyYAML 真解析校验），
// 这边是 1:1 转写。改任何一边都必须同步另一边，并重跑：
//     python3 tools/forms/forms_props.py
//     tools/forms/run_engine_diff.sh          # 两边逐字节对拍
//
// 设计要点（详见 FORMS.md）：
//   * 只解析表单需要的东西：块映射、块序列（含无缩进序列）、`- key: v` 内联续行、flow 集合、多行标量（不透明）
//   * 锚点 / 别名 / `<<:` 作标记原样保留
//   * 路径：字符串段走映射、整数段走序列项（`proxies[3].server` ⇔ listOf("proxies", 3, "server")）
//   * 写回三层：手术式（只换值的字符区间）→ 块级（只重排该键 / 该项）→ 拒绝（宁可不动）
//   * 序列项操作（P2）：setItem / insertItem / removeItem / moveItem 只动那一项的行区间，其它项逐字节不变
//   * 保真：除编辑过的键 / 项，其它内容逐字节不变；改回原值可逐字节还原；CRLF 保留

/** 值渲染：标量 / flow / 块式。对应 forms_model.py 的 render_scalar / render_flow / render_block。 */
object YamlValue {

    private val LOOKS_NUM = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    private val LOOKS_BOOL = setOf(
        "true", "false", "yes", "no", "on", "off", "null", "~",
        "True", "False", "Null", "TRUE", "FALSE", "NULL",
    )
    private const val FORBIDDEN_START = "-?:,[]{}#&*!|>'\"%@`"

    /** YAML plain 标量是否还得加引号。 */
    fun needsQuote(s: String): Boolean {
        if (s.isEmpty()) return true
        if (s != s.trim()) return true
        if (s in LOOKS_BOOL || LOOKS_NUM.matches(s)) return true
        if (s[0] in FORBIDDEN_START) return true
        if (s.contains(": ") || s.endsWith(":")) return true
        if (s.contains(" #")) return true
        if (s.any { it.code < 0x20 }) return true
        return false
    }

    private fun quote(s: String): String = "'" + s.replace("'", "''") + "'"

    /** 块上下文的键：只在 YAML 真需要时加引号（中文名不加，和手写配置一致）。 */
    fun renderKey(key: String): String = if (needsQuote(key)) quote(key) else key

    /** flow 上下文（`{…}` 里）的键：逗号与括号也得引起来。 */
    fun renderKeyFlow(key: String): String = renderScalarFlow(key)

    fun renderScalar(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is String -> if (needsQuote(v)) quote(v) else v
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Float -> renderScalar(v.toDouble())
        else -> v.toString()
    }

    /** 含换行的字符串只能写成 `|` 块（引号里的裸换行会被 YAML 折叠成空格，证书这类内容会坏）。 */
    fun isMultiline(v: Any?): Boolean = v is String && v.contains('\n')

    /** `head: |` / `head: |-` + 缩进的正文行。末尾有换行用 `|`（保留一个），否则 `|-`。 */
    fun renderMultiline(head: String, v: String, indent: Int): List<String> {
        val keep = v.endsWith("\n")
        val body = (if (keep) v.substring(0, v.length - 1) else v).split('\n')
        val pad = " ".repeat(indent)
        val out = ArrayList<String>()
        out.add(head + if (keep) " |" else " |-")
        for (ln in body) out.add(if (ln.isEmpty()) "" else pad + ln)
        return out
    }

    /** flow 上下文（`[...]` / `{...}` 里）的标量：逗号与括号在这里是分隔符，必须加引号。 */
    fun renderScalarFlow(v: Any?): String {
        if (v is String && !needsQuote(v) && v.any { it == ',' || it == '[' || it == ']' || it == '{' || it == '}' }) {
            return quote(v)
        }
        return renderScalar(v)
    }

    /** 简单值渲染成 flow（短、无深层嵌套时可用），否则 null。 */
    fun renderFlow(v: Any?): String? = when (v) {
        is List<*> -> {
            val parts = v.map { renderFlow(it) }
            if (parts.any { it == null }) null
            else {
                val out = "[" + parts.joinToString(", ") + "]"
                if (out.length <= 160) out else null
            }
        }
        is Map<*, *> -> {
            val parts = ArrayList<String>()
            var ok = true
            for ((k, x) in v) {
                val xs = renderFlow(x)
                if (xs == null) { ok = false; break }
                parts.add("${renderKeyFlow(k.toString())}: $xs")
            }
            if (!ok) null
            else {
                val out = "{" + parts.joinToString(", ") + "}"
                if (out.length <= 160) out else null
            }
        }
        else -> if (isMultiline(v)) null else renderScalarFlow(v)
    }

    /** 把一个值按块式风格渲染进 out（缩进 indent 空格）。 */
    fun renderBlock(v: Any?, indent: Int, out: MutableList<String>) {
        val pad = " ".repeat(indent)
        when (v) {
            is Map<*, *> -> {
                if (v.isEmpty()) { out.add(pad + "{}"); return }
                for ((k, x) in v) {
                    val leaf = leafOf(x)
                    if (isMultiline(x)) out.addAll(renderMultiline("$pad${renderKey(k.toString())}:", x as String, indent + 2))
                    else if (leaf != null) out.add("$pad${renderKey(k.toString())}: $leaf")
                    else {
                        out.add("$pad${renderKey(k.toString())}:")
                        renderBlock(x, indent + 2, out)
                    }
                }
            }
            is List<*> -> {
                if (v.isEmpty()) { out.add(pad + "[]"); return }
                for (x in v) {
                    // 映射项一律块式（`- name: x` + 续行）：这是配置里代理/代理组的惯用写法，也最好读
                    val leaf = if (x is Map<*, *> && x.isNotEmpty()) null else leafOf(x)
                    if (isMultiline(x)) out.addAll(renderMultiline("$pad-", x as String, indent + 2))
                    else if (leaf != null) out.add("$pad- $leaf")
                    else if (x is Map<*, *> && x.isNotEmpty()) {
                        val sub = ArrayList<String>()
                        renderBlock(x, indent + 2, sub)
                        out.add("$pad- " + sub[0].trimStart())
                        for (i in 1 until sub.size) out.add(sub[i])
                    } else {
                        out.add("$pad-")
                        renderBlock(x, indent + 2, out)
                    }
                }
            }
            // 多行字符串不会走到这里：有键/项前缀的调用方都已按 `|` 块写（见 renderMultiline）
            else -> out.add(pad + renderScalar(v))
        }
    }

    /** `key: 叶子` / `- 叶子` 里能写的叶子：标量，或装得下的 flow 集合；否则 null（得走块式）。 */
    fun leafOf(v: Any?): String? =
        if (v is Map<*, *> || v is List<*>) renderFlow(v) else if (isMultiline(v)) null else renderScalar(v)

    /** flow 序列里的一项：标量按 flow 规则加引号，集合渲染成 flow。 */
    fun flowLeafOf(v: Any?): String? =
        if (v is Map<*, *> || v is List<*>) renderFlow(v) else if (isMultiline(v)) null else renderScalarFlow(v)

    /** 写成「key: 值」单行时用的叶子：标量、空集合（`[]` / `{}`）；非空集合与多行字符串一律走块式。 */
    fun inlineLeaf(v: Any?): String? = when (v) {
        is List<*> -> if (v.isEmpty()) "[]" else null
        is Map<*, *> -> if (v.isEmpty()) "{}" else null
        else -> if (isMultiline(v)) null else renderScalar(v)
    }

    /**
     * 渲染一个键及其值：prefix 是键前面的原文（缩进或 `- `），标量/空集合单行，否则块式。
     * anchor 是原值上的 `&锚点`：整块重写时必须带回去（`key: &a []` / `key: &a |` / `key: &a` + 块体），
     * 否则后面的 `*a` 别名会悬空，写出无效 YAML。
     */
    fun renderKeyValue(prefix: String, key: String, value: Any?, indent: Int, anchor: String? = null): List<String> {
        val head = "$prefix${renderKey(key)}:" + (if (anchor != null) " &$anchor" else "")
        val leaf = inlineLeaf(value)
        if (leaf != null) return listOf("$head $leaf")
        if (isMultiline(value)) return renderMultiline(head, value as String, indent + 2)
        val out = ArrayList<String>()
        out.add(head)
        renderBlock(value, indent + 2, out)
        return out
    }
}


// ---------------------------------------------------------------- 行工具（顶层）

private fun isBlankOrComment(line: String): Boolean {
    val t = line.trim()
    return t.isEmpty() || t.startsWith("#")
}

private fun indentOf(line: String): Int {
    var i = 0
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
    return i
}

/** 去掉行尾注释，返回 (内容, 注释)。 */
private fun stripComment(s: String): Pair<String, String> {
    var inSingle = false
    var inDouble = false
    var cut = -1
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (inSingle) { if (c == '\'') inSingle = false }
        else if (inDouble) { if (c == '"') inDouble = false }
        else when (c) {
            '\'' -> inSingle = true
            '"' -> inDouble = true
            '#' -> if (i == 0 || s[i - 1] == ' ' || s[i - 1] == '\t') cut = i
        }
        if (cut >= 0) break
        i++
    }
    if (cut < 0) return s.trimEnd() to ""
    val content = s.substring(0, cut).trimEnd()
    return content to s.substring(content.length)
}

/** 扫描引号串，返回闭合引号后的位置。 */
private fun scanQuoted(s: String, from: Int): Int {
    val q = s[from]
    var i = from + 1
    while (i < s.length) {
        if (q == '\'' && s[i] == '\'') {
            if (i + 1 < s.length && s[i + 1] == '\'') { i += 2; continue }
            return i + 1
        }
        if (q == '"' && s[i] == '\\') { i += 2; continue }
        if (q == '"' && s[i] == '"') return i + 1
        i++
    }
    return s.length
}

/** 扫描一个 flow 值（可嵌套），返回结束位置（不含分隔逗号）。 */
private fun scanFlowValue(s: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < s.length) {
        val c = s[i]
        if (c == '\'' || c == '"') { i = scanQuoted(s, i); continue }
        if (c == '[' || c == '{') depth++
        else if (c == ']' || c == '}') { if (depth == 0) return i else depth-- }
        else if (c == ',' && depth == 0) return i
        i++
    }
    return i
}

// 键必须把「引号形态」排在通用形态之前：`'geosite:cn':` 里的冒号不能当成键值分隔符。
// 冒号后面必须是空白或行尾（YAML 的键值分隔规则）：`IP-CIDR,2001:db8::/32,DIRECT` 这类带冒号的标量不是键。
// 裸键里允许夹冒号（`geosite:cn: 223.5.5.5`、`rule-set:a,b: [...]` 这类 nameserver-policy 键）：懒匹配到第一个
// 「冒号 + 空白 / 行尾」才算分隔符；`#` 不许出现在裸键里（否则 `foo # c: x` 会把注释吞进键）；裸键不能以引号开头
// （`"a: b"` 是一个带冒号的引号标量，不是键 `"a`）。
private val KEY_RE = Regex(
    "^(?<indent>[ \\t]*)(?<key>(?:'[^']*')|(?:\"[^\"]*\")|(?:[^#:\\s'\"][^#]*?))\\s*:(?<rest>\\s.*|)$"
)
private val SEQ_RE = Regex("^(?<indent>[ \\t]*)-(?<rest>\\s.*|)$")
private val ANCHOR_RE = Regex("^&(\\S+)\\s*(.*)$")
private val TAG_RE = Regex("^!!\\S+\\s*(.*)$")
private val ALIAS_RE = Regex("^\\*[^\\s,{}\\[\\]]+.*")

/** flow 映射里的一段的列区间：键、键起止、值起止。 */
private class FlowSpan(val key: String, val keyStart: Int, val keyEnd: Int, val valStart: Int, val valEnd: Int)

/** 解析 flow 映射文本（text[start] 是 '{'），返回 (各段列区间, 闭合位置 + 1)。对应 parse_flow_entries。 */
private fun parseFlowEntries(s: String, start: Int): Pair<List<FlowSpan>, Int> {
    val out = ArrayList<FlowSpan>()
    var i = start + 1
    while (i < s.length) {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == ',')) i++
        if (i >= s.length || s[i] == '}') return out to (i + 1)
        val keyStart = i
        if (s[i] == '\'' || s[i] == '"') {
            i = scanQuoted(s, i)
        } else {
            while (i < s.length && s[i] != ':' && s[i] != ',' && s[i] != '}') i++
        }
        val keyEnd = i
        val keyRaw = s.substring(keyStart, keyEnd).trim()
        val key = if (keyRaw.length >= 2 && (keyRaw[0] == '\'' || keyRaw[0] == '"') &&
            keyRaw.last() == keyRaw[0]
        ) keyRaw.substring(1, keyRaw.length - 1) else keyRaw
        while (i < s.length && s[i] != ':') { if (s[i] == '}') return out to (i + 1); i++ }
        i++
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        val valStart = i
        i = scanFlowValue(s, i)
        // `{k: ]`：括号不配对，扫描停在别人的闭合符上，继续扫只会原地打转 —— 按解析失败处理
        if (i < s.length && s[i] == ']') throw IllegalArgumentException("flow 映射括号不配对")
        out.add(FlowSpan(key, keyStart, keyEnd, valStart, i))
    }
    return out to (i + 1)
}

/** 解析 flow 序列（text[start] 是 '['），返回 ([(项起, 项止)], 闭合位置 + 1)。对应 parse_flow_seq_items。 */
private fun parseFlowSeqItems(s: String, start: Int): Pair<List<Pair<Int, Int>>, Int> {
    val out = ArrayList<Pair<Int, Int>>()
    var i = start + 1
    while (i < s.length) {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == ',')) i++
        if (i >= s.length || s[i] == ']') return out to (i + 1)
        val a = i
        i = scanFlowValue(s, i)
        // `[1, 2}`：扫描停在不配对的 `}` 上没有前进，再循环就是死循环 —— 按解析失败处理
        if (i == a) throw IllegalArgumentException("flow 序列括号不配对")
        out.add(a to i)
    }
    return out to (i + 1)
}

/** 一个 YAML 节点。start/end 是行区间 [start, end)，列区间只在 inline 时有意义。 */
class YamlNode(
    var kind: Kind,
    var start: Int,
    var end: Int,
    var indent: Int = 0,
    var inline: Boolean = false,
    var valueStart: Int = 0,
    var valueEnd: Int = 0,
    var anchor: String? = null,
    var alias: String? = null,
    var merge: Boolean = false,
    var multi: Boolean = false,
    var flow: Boolean = false,
    var flowStart: Int = 0,
    var flowEnd: Int = 0,
    /** 作为块序列的一项时，`-` 所在行（项的行区间从这里起算）；-1 表示不是块序列项。 */
    var dash: Int = -1,
) {
    enum class Kind { MAP, SEQ, SCALAR, RAW }

    val entries = ArrayList<YamlEntry>()
    val items = ArrayList<YamlNode>()
}

class YamlEntry(
    val key: String,
    val keyIndent: Int,
    val node: YamlNode?,
    val line: Int,
) {
    val isMerge: Boolean get() = key == "<<"
}


/** 行树解析器。放成类而不是局部函数，是因为几个解析函数互相递归（局部函数不能前向引用）。 */
private class YamlParser(private val lines: List<String>) {

    fun parseRoot(): YamlNode {
        val root = YamlNode(YamlNode.Kind.MAP, 0, lines.size)
        var i = 0
        while (i < lines.size) {
            if (isBlankOrComment(lines[i])) { i++; continue }
            val ind = indentOf(lines[i])
            val (node, after) = parseBlock(i, ind)
            if (node.kind == YamlNode.Kind.MAP) root.entries.addAll(node.entries)
            else root.entries.add(YamlEntry(if (node.kind == YamlNode.Kind.SEQ) "__seq__" else "__scalar__", 0, node, i))
            root.end = after
            i = if (after > i) after else i + 1
        }
        return root
    }

    fun scalarNode(lineNo: Int, col: Int, endCol: Int): YamlNode {
        val line = lines[lineNo]
        val raw = line.substring(col.coerceIn(0, line.length), endCol.coerceIn(0, line.length))
        val (content, _) = stripComment(raw)
        val valueStart = col + (raw.length - raw.trimStart().length)
        val n = YamlNode(YamlNode.Kind.SCALAR, lineNo, lineNo + 1, inline = true,
            valueStart = valueStart, valueEnd = col + content.length)
        var body = content.trim()
        val am = ANCHOR_RE.find(body)
        if (am != null) {
            n.anchor = am.groupValues[1]
            body = am.groupValues[2].trim()
            if (body.isNotEmpty()) {
                val idx = line.indexOf(body, n.valueStart)
                if (idx >= 0) { n.valueStart = idx; n.valueEnd = idx + body.length }
            }
        }
        val tm = TAG_RE.find(body)
        if (tm != null) {
            body = tm.groupValues[1].trim()
            if (body.isNotEmpty()) {
                val idx = line.indexOf(body, n.valueStart)
                if (idx >= 0) { n.valueStart = idx; n.valueEnd = idx + body.length }
            }
        }
        when {
            ALIAS_RE.matches(body) -> { n.kind = YamlNode.Kind.RAW; n.alias = body.trimStart('*').trim() }
            body.startsWith("{") -> {
                try {
                    val base = n.valueStart
                    val (spans, close) = parseFlowEntries(line, base)
                    n.kind = YamlNode.Kind.MAP
                    n.flow = true
                    n.flowStart = base
                    n.flowEnd = close.coerceAtMost(line.length)
                    for (sp in spans) {
                        n.entries.add(YamlEntry(sp.key, base, scalarNode(lineNo, sp.valStart, sp.valEnd), lineNo))
                    }
                } catch (_: Exception) { n.kind = YamlNode.Kind.RAW }
            }
            body.startsWith("[") -> {
                try {
                    val base = n.valueStart
                    val (spans, close) = parseFlowSeqItems(line, base)
                    n.kind = YamlNode.Kind.SEQ
                    n.flow = true
                    n.flowStart = base
                    n.flowEnd = close.coerceAtMost(line.length)
                    for ((a, b) in spans) n.items.add(scalarNode(lineNo, a, b))
                } catch (_: Exception) { n.kind = YamlNode.Kind.RAW }
            }
            body.startsWith("|") || body.startsWith(">") -> {
                n.kind = YamlNode.Kind.RAW
                n.multi = true
                var j = lineNo + 1
                val base = indentOf(line)
                while (j < lines.size) {
                    if (lines[j].isBlank()) { j++; continue }
                    if (indentOf(lines[j]) > base) { j++; continue }
                    break
                }
                // 块不吸收尾随空行（和块式映射 / 序列一致）：整块重写或删除时，和下一个键之间的空行留在原地
                while (j - 1 > lineNo && lines[j - 1].isBlank()) j--
                n.end = j
            }
        }
        return n
    }

    /** 解析 [from, …) 里缩进为 indent 的块，返回 (节点, 下一个未消费行号)。块不吸收尾随空行/注释。 */
    fun parseBlock(from: Int, indent: Int): Pair<YamlNode, Int> {
        var i = from
        var node: YamlNode? = null
        var lastEnd = from
        while (i < lines.size) {
            val line = lines[i]
            if (isBlankOrComment(line)) { i++; continue }
            val ind = indentOf(line)
            if (ind != indent) break
            val mSeq = SEQ_RE.find(line)
            val mKey = if (mSeq == null) KEY_RE.find(line) else null
            val cur = node
            if (mSeq != null) {
                // 映射块后面跟同缩进的 `- `：不是这个块的内容（无缩进序列由 parseMapEntry 接管）
                if (cur != null && cur.kind != YamlNode.Kind.SEQ) break
                val n = cur ?: YamlNode(YamlNode.Kind.SEQ, i, i, indent = indent)
                val (item, after) = parseSeqItem(i, indent)
                n.items.add(item)
                i = after; lastEnd = after; n.end = after
                node = n
            } else if (mKey != null) {
                // 无缩进序列到头了：同缩进的下一个键属于上层映射
                if (cur != null && cur.kind != YamlNode.Kind.MAP) break
                val n = cur ?: YamlNode(YamlNode.Kind.MAP, i, i, indent = indent)
                val (entry, after) = parseMapEntry(i, indent, null)
                n.entries.add(entry)
                i = after; lastEnd = after; n.end = after
                node = n
            } else {
                if (cur == null) {
                    val n = YamlNode(YamlNode.Kind.SCALAR, i, i + 1, indent = indent,
                        valueStart = ind, valueEnd = line.length)
                    i++; lastEnd = i
                    return n to lastEnd
                }
                break
            }
        }
        val n = node ?: return YamlNode(YamlNode.Kind.MAP, lastEnd, lastEnd, indent = indent) to lastEnd
        return n to lastEnd
    }

    fun parseSeqItem(at: Int, indent: Int): Pair<YamlNode, Int> {
        val line = lines[at]
        val m = SEQ_RE.find(line)!!
        val rest = m.groups["rest"]!!.value
        val contentCol = indent + 1 + (if (rest.isNotBlank()) rest.length - rest.trimStart().length else 0)
        if (rest.isBlank()) {
            var nxt = at + 1
            while (nxt < lines.size && isBlankOrComment(lines[nxt])) nxt++
            if (nxt < lines.size && indentOf(lines[nxt]) > indent) {
                val (inner, after) = parseBlock(nxt, indentOf(lines[nxt]))
                inner.dash = at
                return inner to after
            }
            val empty = YamlNode(YamlNode.Kind.SCALAR, at, at + 1, indent = indent,
                valueStart = indent, valueEnd = line.length)
            empty.dash = at
            return empty to (at + 1)
        }
        val body = rest.trim()
        // `&锚点` / `!!标签` 前缀属于项节点，不是首键的一部分：先跳过前缀再判断形态。
        // 不跳过的话 `- &tpl { … }` 会被 KEY_RE 匹出垃圾键（`&tpl { name`），写键时落到 flow 行
        // 下面追加块行、把 YAML 写坏（锚点里的参数「没有变」，追加的重复键反而全量覆盖原值）；
        // `- &tpl`（块式项）则会被当成空标量，下面缩进的整块内容全部丢失。
        var probe = body
        var probeCol = contentCol
        var itemAnchor: String? = null
        while (probe.isNotEmpty()) {
            val am = ANCHOR_RE.find(probe)
            val tm = TAG_RE.find(probe)
            when {
                am != null -> {
                    if (itemAnchor == null) itemAnchor = am.groupValues[1]
                    probeCol += am.groupValues[0].length - am.groupValues[2].length
                    probe = am.groupValues[2].trimStart()
                }

                tm != null -> {
                    probeCol += tm.groupValues[0].length - tm.groupValues[1].length
                    probe = tm.groupValues[1].trimStart()
                }

                else -> break
            }
        }
        if (probe.isEmpty()) {
            // 只剩前缀（`- &tpl`）：项内容在后续更深缩进的行上；没有就是只带锚点的空项
            var nxt = at + 1
            while (nxt < lines.size && isBlankOrComment(lines[nxt])) nxt++
            if (nxt < lines.size && indentOf(lines[nxt]) > indent) {
                val (inner, after) = parseBlock(nxt, indentOf(lines[nxt]))
                inner.dash = at
                inner.anchor = inner.anchor ?: itemAnchor
                return inner to after
            }
            val empty = YamlNode(YamlNode.Kind.SCALAR, at, at + 1, indent = indent,
                valueStart = line.length, valueEnd = line.length, anchor = itemAnchor)
            empty.dash = at
            return empty to (at + 1)
        }
        // 内联映射：- key: value（flow 集合 `- {…}` / `- […]` 与多行标量不算；按跳过前缀后的形态判断）
        val mKey = if ("{[|>".indexOf(probe[0]) >= 0) null else KEY_RE.find(probe)
        if (mKey != null) {
            val subIndent = probeCol
            val node = YamlNode(YamlNode.Kind.MAP, at, at + 1, indent = subIndent, anchor = itemAnchor)
            node.dash = at
            val (entry, after) = parseMapEntry(at, subIndent, probeCol)
            node.entries.add(entry)
            node.end = after          // 首键的值可能是多行块，项的结束行必须跟着走
            var j = after
            while (j < lines.size) {
                if (isBlankOrComment(lines[j])) { j++; continue }
                if (indentOf(lines[j]) != subIndent) break
                if (SEQ_RE.find(lines[j]) != null) break
                if (KEY_RE.find(lines[j]) == null) break
                val (e2, j2) = parseMapEntry(j, subIndent, null)
                node.entries.add(e2)
                j = j2
                node.end = j
            }
            return node to node.end
        }
        val n = scalarNode(at, contentCol, line.length)
        n.dash = at
        return n to n.end
    }

    fun parseMapEntry(at: Int, indent: Int, sliceFrom: Int?): Pair<YamlEntry, Int> {
        val line = lines[at]
        val subject = if (sliceFrom == null) line else line.substring(sliceFrom)
        val m = KEY_RE.find(subject)!!
        val prefixLen = sliceFrom ?: 0
        val keyRaw = m.groups["key"]!!.value.trim()
        val key = if (keyRaw.length >= 2 && (keyRaw[0] == '\'' || keyRaw[0] == '"') &&
            keyRaw.last() == keyRaw[0]
        ) keyRaw.substring(1, keyRaw.length - 1) else keyRaw
        val rest = m.groups["rest"]!!.value
        val restStart = prefixLen + m.groups["rest"]!!.range.first
        val (content, _) = stripComment(rest)
        var stripped = content.trim()
        var anchor: String? = null
        while (true) {
            val am = ANCHOR_RE.find(stripped)
            val tm = TAG_RE.find(stripped)
            if (am != null) {
                anchor = am.groupValues[1]
                stripped = am.groupValues[2].trim()
            } else if (tm != null) {
                stripped = tm.groupValues[1].trim()
            } else break
        }
        if (stripped.isEmpty()) {
            // 值在后续更深缩进的行；`key: # 注释` 也算空值
            var j = at + 1
            while (j < lines.size && isBlankOrComment(lines[j])) j++
            if (j < lines.size && indentOf(lines[j]) > indent) {
                val (inner, after) = parseBlock(j, indentOf(lines[j]))
                inner.anchor = anchor
                return YamlEntry(key, indent, inner, at) to after
            }
            // 无缩进序列：`key:` 的下一行是同缩进的 `- …`（PyYAML 默认输出就是这样）
            if (j < lines.size && indentOf(lines[j]) == indent && SEQ_RE.find(lines[j]) != null) {
                val (inner, after) = parseBlock(j, indent)
                inner.anchor = anchor
                return YamlEntry(key, indent, inner, at) to after
            }
            val empty = YamlNode(YamlNode.Kind.SCALAR, at, at + 1, indent = indent,
                valueStart = line.length, valueEnd = line.length, anchor = anchor)
            return YamlEntry(key, indent, empty, at) to (at + 1)
        }
        val valCol = restStart + (rest.length - rest.trimStart().length)
        val n = scalarNode(at, valCol, line.length)
        return YamlEntry(key, indent, n, at) to n.end
    }
}

/** 文档：行数组 + 根节点。行内容一律不含行尾符；eol 单独记。 */
class YamlDoc private constructor(
    val eol: String,
    val lines: MutableList<String>,
    val hasBom: Boolean,
    val root: YamlNode,
) {
    fun dump(): String = (if (hasBom) "\uFEFF" else "") + lines.joinToString(eol)

    // ---------------------------------------------------------------- 查询

    fun findEntry(node: YamlNode?, key: String): YamlEntry? {
        if (node == null || node.kind != YamlNode.Kind.MAP) return null
        for (e in node.entries) if (e.key == key && !e.isMerge) return e
        return null
    }

    /** 沿一段路径下钻：整数段进序列（下标越界 → null），字符串段进映射。 */
    fun child(node: YamlNode?, seg: Any): YamlNode? {
        if (node == null) return null
        if (seg is Int) {
            if (node.kind != YamlNode.Kind.SEQ || seg < 0 || seg >= node.items.size) return null
            return node.items[seg]
        }
        return findEntry(node, seg.toString())?.node
    }

    fun get(path: String): YamlNode? = get(splitPath(path))

    fun get(path: List<Any>): YamlNode? {
        var cur: YamlNode? = root
        for (part in path) cur = child(cur, part) ?: return null
        return cur
    }

    /** 沿路径走到父节点，终点必须是映射（表单只往映射里写键）。allowNull 时 `key:` 空值视为空映射。 */
    fun resolveParent(path: List<Any>, allowNull: Boolean = false): YamlNode? {
        if (path.isEmpty()) return if (root.kind == YamlNode.Kind.MAP) root else null
        var cur: YamlNode? = root
        for (part in path) cur = child(cur, part) ?: return null
        val n = cur ?: return null
        if (n.kind == YamlNode.Kind.MAP) return n
        return if (allowNull && isNullText(n)) n else null
    }

    fun canSet(path: String): Boolean = canSet(splitPath(path))

    /** 表单能不能编辑这个路径：父级要能沿映射/序列项走通；序列项不会凭空新建（追加走 insertItem）。 */
    fun canSet(parts: List<Any>): Boolean {
        if (parts.isEmpty()) return false
        val parentPath = parts.dropLast(1)
        val key = parts.last()
        if (key is Int) {
            val seq = get(parentPath) ?: return false
            return seq.kind == YamlNode.Kind.SEQ && key >= 0 && key < seq.items.size
        }
        if (resolveParent(parentPath) != null) return true
        var cur: YamlNode? = root
        for (part in parentPath) {
            val n = cur ?: return false
            if (part is Int) {
                cur = child(n, part) ?: return false
                continue
            }
            if (n.kind != YamlNode.Kind.MAP) return false
            val e = findEntry(n, part.toString()) ?: return true    // 后面的层级都可以新建
            cur = e.node
        }
        return cur != null && cur.kind == YamlNode.Kind.MAP
    }

    // ---------------------------------------------------------------- 内部工具

    fun isNullValue(n: YamlNode?): Boolean = when {
        n == null -> true
        n.kind == YamlNode.Kind.MAP -> n.entries.isEmpty()
        n.kind == YamlNode.Kind.SEQ -> n.items.isEmpty()
        n.kind == YamlNode.Kind.SCALAR && !n.multi -> n.valueStart >= n.valueEnd
        else -> false
    }

    fun isNullText(n: YamlNode?): Boolean {
        if (isNullValue(n)) return true
        if (n != null && n.kind == YamlNode.Kind.SCALAR) {
            val t = lines[n.start].substring(
                n.valueStart.coerceAtLeast(0).coerceAtMost(lines[n.start].length),
                n.valueEnd.coerceAtLeast(0).coerceAtMost(lines[n.start].length),
            ).trim()
            return t == "null" || t == "~" || t == "Null" || t == "NULL"
        }
        return false
    }

    /** 一个键（含其值）占的行区间 [start, end)。 */
    fun lineBounds(entry: YamlEntry): Pair<Int, Int> {
        val n = entry.node ?: return entry.line to (entry.line + 1)
        return entry.line to n.end
    }

    /** 块序列里一项占据的行区间 [start, end)：从 `-` 那行起到值结束。 */
    fun itemBounds(item: YamlNode): Pair<Int, Int> {
        val start = if (item.dash >= 0) item.dash else item.start
        return start to maxOf(item.end, start + 1)
    }

    /** 键前面的原文：普通键是缩进空白；序列项首键是 `- `（必须原样保留，否则把项的 `-` 吃掉）。 */
    fun keyPrefix(entry: YamlEntry): String {
        val line = lines[entry.line]
        return line.substring(0, entry.keyIndent.coerceAtMost(line.length))
    }

    fun childEnd(parent: YamlNode): Int {
        if (parent.entries.isEmpty()) return if (parent.start < lines.size) parent.start + 1 else lines.size
        val last = parent.entries.last()
        return lineBounds(last).second
    }

    companion object {

        /** `a.b.c` → [a, b, c]；`a[3].c` → [a, 3, c]；引号段可含 `.` / `[`。对应 _split_path。 */
        fun splitPath(path: String): List<Any> {
            val out = ArrayList<Any>()
            var buf = StringBuilder()
            var hasBuf = false
            var quote: Char? = null
            var i = 0
            while (i < path.length) {
                val ch = path[i]
                if (quote != null) {
                    if (ch == quote) quote = null else buf.append(ch)
                } else if (ch == '\'' || ch == '"') {
                    quote = ch
                    hasBuf = true
                } else if (ch == '[') {
                    if (buf.isNotEmpty() || hasBuf) { out.add(buf.toString()); buf = StringBuilder(); hasBuf = false }
                    var j = path.indexOf(']', i)
                    if (j < 0) j = path.length
                    out.add(path.substring(i + 1, j).trim().toIntOrNull() ?: 0)
                    i = j
                } else if (ch == '.') {
                    if (buf.isNotEmpty() || hasBuf) out.add(buf.toString())
                    buf = StringBuilder(); hasBuf = false
                } else buf.append(ch)
                i++
            }
            if (buf.isNotEmpty() || hasBuf) out.add(buf.toString())
            return out
        }

        /** 把用户起的名字（代理集合名 / 子规则名…）变成字符串路径里的一段：含 `.` `[` 引号时加引号。 */
        fun quoteSeg(name: String): String {
            if (name.isEmpty() || name.any { it == '.' || it == '[' || it == ']' || it == '\'' || it == '"' }) {
                val q = if (name.contains('\'') && !name.contains('"')) '"' else '\''
                return q + name + q
            }
            return name
        }

        fun parse(text: String): YamlDoc {
            val eol = if (text.contains("\r\n")) "\r\n" else "\n"
            val hasBom = text.startsWith("\uFEFF")
            val body = if (hasBom) text.substring(1) else text
            val lines = ArrayList<String>(body.split(if (eol == "\r\n") "\r\n" else "\n"))
            val root = YamlParser(lines).parseRoot()
            return YamlDoc(eol, lines, hasBom, root)
        }
    }
}

/** 写回：手术式优先，块级次之，路径不可编辑则原样返回。对应 forms_model.py 的 set_value / remove_key / 序列项操作。 */
object YamlPatch {

    fun setValue(doc: YamlDoc, path: String, value: Any?): YamlDoc = setValue(doc, YamlDoc.splitPath(path), value)

    fun setValue(doc: YamlDoc, parts: List<Any>, value: Any?): YamlDoc {
        if (parts.isEmpty()) return doc
        val parentPath = parts.dropLast(1)
        val last = parts.last()
        if (last is Int) return setItem(doc, parentPath, last, value)
        val key = last.toString()
        val parent = doc.resolveParent(parentPath)

        if (parent != null && parent.flow) return flowSet(doc, parent, key, value)
        if (parent != null) {
            val entry = doc.findEntry(parent, key)
            val n = entry?.node
            if (entry != null && n != null && n.flow) {
                // 值本身是 flow 集合：整段替换（保持行内）；太长渲染不下就退回块式
                val rendered = YamlValue.renderFlow(value) ?: return replaceEntryBlock(doc, entry, value)
                val lines = ArrayList(doc.lines)
                val line = lines[n.start]
                lines[n.start] = line.substring(0, n.flowStart) + rendered +
                    line.substring(n.flowEnd.coerceAtMost(line.length))
                return reparse(doc, lines)
            }
            // 行内标量 / 解析不了的 flow 段：只替换值那一段文本。`|` 多行块（续行在后面）和 `*别名`
            // 不走这里：前者必须整块重写（否则续行残留成垃圾），后者按 Python 规范整块重写（列表写成块式）。
            if (entry != null && n != null && n.inline &&
                (n.kind == YamlNode.Kind.SCALAR || (n.kind == YamlNode.Kind.RAW && !n.multi && n.alias == null))
            ) {
                val leaf = YamlValue.leafOf(value)
                if (leaf != null) {
                    val lines = ArrayList(doc.lines)
                    val line = lines[n.start]
                    val before = line.substring(0, n.valueStart.coerceAtMost(line.length))
                    val after = line.substring(n.valueEnd.coerceAtMost(line.length))
                    val prefix = if (n.anchor != null) "&${n.anchor} " else ""
                    lines[n.start] = before + prefix + leaf + after
                    return reparse(doc, lines)
                }
                return replaceEntryBlock(doc, entry, value)
            }
            if (entry != null) return replaceEntryBlock(doc, entry, value)
        }
        if (parent == null) {
            return insertKey(doc, parentPath, key, value, asEmpty = false) ?: doc
        }
        val lines = ArrayList(doc.lines)
        val insertAt = doc.childEnd(parent)
        val indent = parent.indent
        lines.addAll(insertAt, YamlValue.renderKeyValue(" ".repeat(indent), key, value, indent))
        return reparse(doc, lines)
    }

    fun removeKey(doc: YamlDoc, path: String): YamlDoc = removeKey(doc, YamlDoc.splitPath(path))

    fun removeKey(doc: YamlDoc, parts: List<Any>): YamlDoc {
        if (parts.isEmpty()) return doc
        val parentPath = parts.dropLast(1)
        val last = parts.last()
        if (last is Int) return removeItem(doc, parentPath, last)
        val key = last.toString()
        var parent: YamlNode? = doc.root
        for (part in parentPath) parent = doc.child(parent, part) ?: return doc
        val p = parent ?: return doc
        if (p.flow) {
            val lines = ArrayList(doc.lines)
            val line = lines[p.start]
            val span = line.substring(p.flowStart, p.flowEnd.coerceAtMost(line.length))
            lines[p.start] = line.substring(0, p.flowStart) + flowRemove(span, key) +
                line.substring(p.flowEnd.coerceAtMost(line.length))
            return reparse(doc, lines)
        }
        val entry = doc.findEntry(p, key) ?: return doc
        val (start, end) = doc.lineBounds(entry)
        val lines = ArrayList(doc.lines)
        val prefix = doc.keyPrefix(entry)
        if (prefix.isNotBlank()) {
            // 序列项的首键（`- name: x`）：删掉这行会连 `-` 一起删掉。把下一个键提到 `-` 这一行来。
            val idx = p.entries.indexOf(entry)
            if (idx + 1 >= p.entries.size) return doc      // 项里只剩这一个键：不删（删项请用 removeItem）
            val nxt = p.entries[idx + 1]
            val nxtStart = doc.lineBounds(nxt).first
            val promoted = prefix + lines[nxtStart].substring(nxt.keyIndent.coerceAtMost(lines[nxtStart].length))
            removeRange(lines, start, nxtStart)
            lines[start] = promoted
            return reparse(doc, lines)
        }
        removeRange(lines, start, end)
        return reparse(doc, lines)
    }

    /** 改键名（代理集合 / 规则集合 / 子规则改名）：只替换键那一段文本，值与注释不动。新名字已存在则拒绝。 */
    fun renameKey(doc: YamlDoc, parts: List<Any>, newKey: String): YamlDoc {
        if (parts.isEmpty()) return doc
        val last = parts.last()
        if (last is Int || last.toString() == newKey) return doc
        val key = last.toString()
        var parent: YamlNode? = doc.root
        for (part in parts.dropLast(1)) parent = doc.child(parent, part) ?: return doc
        val p = parent ?: return doc
        if (p.kind != YamlNode.Kind.MAP || doc.findEntry(p, newKey) != null) return doc
        val lines = ArrayList(doc.lines)
        if (p.flow) {
            val line = lines[p.start]
            val span = line.substring(p.flowStart, p.flowEnd.coerceAtMost(line.length))
            lines[p.start] = line.substring(0, p.flowStart) + flowRename(span, key, newKey) +
                line.substring(p.flowEnd.coerceAtMost(line.length))
            return reparse(doc, lines)
        }
        val entry = doc.findEntry(p, key) ?: return doc
        val line = lines[entry.line]
        val m = KEY_RE.find(line.substring(entry.keyIndent.coerceAtMost(line.length))) ?: return doc
        val range = m.groups["key"]!!.range
        val ks = entry.keyIndent + range.first
        val ke = entry.keyIndent + range.last + 1
        lines[entry.line] = line.substring(0, ks) + YamlValue.renderKey(newKey) + line.substring(ke)
        return reparse(doc, lines)
    }

    // ---------------------------------------------------------------- 序列项（P2）

    /** 改序列第 index 项：行内标量 → 手术式只换值；否则只重排这一项的行区间。 */
    fun setItem(doc: YamlDoc, seqPath: List<Any>, index: Int, value: Any?): YamlDoc {
        val seq = doc.get(seqPath) ?: return doc
        if (seq.kind != YamlNode.Kind.SEQ || index < 0 || index >= seq.items.size) return doc
        val item = seq.items[index]
        val lines = ArrayList(doc.lines)
        if (seq.flow) {
            val rendered = YamlValue.flowLeafOf(value) ?: return doc
            val raws = flowRawItems(doc, seq)
            raws[index] = rendered
            return flowSeqRewrite(doc, seqPath, seq, raws)
        }
        val scalarLike = (item.kind == YamlNode.Kind.SCALAR || item.kind == YamlNode.Kind.RAW) && item.inline && !item.multi
        if (scalarLike && value !is Map<*, *> && value !is List<*>) {
            val line = lines[item.start]
            val prefix = if (item.anchor != null) "&${item.anchor} " else ""
            lines[item.start] = line.substring(0, item.valueStart.coerceAtMost(line.length)) + prefix +
                YamlValue.renderScalar(value) + line.substring(item.valueEnd.coerceAtMost(line.length))
            return reparse(doc, lines)
        }
        val (start, end) = doc.itemBounds(item)
        val render = ArrayList<String>()
        YamlValue.renderBlock(listOf(value), seq.indent, render)
        removeRange(lines, start, end)
        lines.addAll(start, render)
        return reparse(doc, lines)
    }

    /** 在序列第 index 项之前插入（index == 项数 → 追加）。序列不存在/为空值时整键新建。 */
    fun insertItem(doc: YamlDoc, seqPath: List<Any>, index: Int, value: Any?): YamlDoc {
        val seq = doc.get(seqPath)
        if (seq == null || (seq.kind != YamlNode.Kind.SEQ && doc.isNullText(seq))) {
            return setValue(doc, seqPath, listOf(value))
        }
        if (seq.kind != YamlNode.Kind.SEQ) return doc
        val idx = index.coerceIn(0, seq.items.size)
        if (seq.flow) {
            val rendered = YamlValue.flowLeafOf(value)
            val raws = flowRawItems(doc, seq)
            if (rendered == null) {
                return if (raws.isEmpty()) setValue(doc, seqPath, listOf(value)) else doc
            }
            raws.add(idx, rendered)
            if (seq.items.isEmpty()) return setValue(doc, seqPath, listOf(value))
            return flowSeqRewrite(doc, seqPath, seq, raws)
        }
        val lines = ArrayList(doc.lines)
        // 插在前一项结束之后（而不是第 index 项的 `-` 之前）：夹在中间的注释继续跟着它原来描述的那一项
        val at = if (idx > 0) doc.itemBounds(seq.items[idx - 1]).second else doc.itemBounds(seq.items[0]).first
        val render = ArrayList<String>()
        YamlValue.renderBlock(listOf(value), seq.indent, render)
        lines.addAll(at, render)
        return reparse(doc, lines)
    }

    /** 删掉序列第 index 项（只删它的行区间）；删到空就写成 `key: []`。 */
    fun removeItem(doc: YamlDoc, seqPath: List<Any>, index: Int): YamlDoc {
        val seq = doc.get(seqPath) ?: return doc
        if (seq.kind != YamlNode.Kind.SEQ || index < 0 || index >= seq.items.size) return doc
        if (seq.items.size == 1) return setValue(doc, seqPath, emptyList<Any?>())
        if (seq.flow) {
            val raws = flowRawItems(doc, seq)
            raws.removeAt(index)
            return flowSeqRewrite(doc, seqPath, seq, raws)
        }
        val lines = ArrayList(doc.lines)
        val (start, end) = doc.itemBounds(seq.items[index])
        removeRange(lines, start, end)
        return reparse(doc, lines)
    }

    /** 把第 from 项挪到第 to 位：纯粹的行区间重排，不重新渲染任何一项（项之间的注释跟着前一项走）。 */
    fun moveItem(doc: YamlDoc, seqPath: List<Any>, from: Int, to: Int): YamlDoc {
        val seq = doc.get(seqPath) ?: return doc
        if (seq.kind != YamlNode.Kind.SEQ) return doc
        val n = seq.items.size
        if (from < 0 || from >= n || to < 0 || to >= n || from == to) return doc
        if (seq.flow) {
            val raws = flowRawItems(doc, seq)
            val moved = raws.removeAt(from)
            raws.add(to, moved)
            return flowSeqRewrite(doc, seqPath, seq, raws)
        }
        val starts = seq.items.map { doc.itemBounds(it).first }
        val ends = starts.drop(1) + seq.end
        val lines = ArrayList(doc.lines)
        val chunks = ArrayList<List<String>>()
        for (i in 0 until n) chunks.add(lines.subList(starts[i], ends[i]).toList())
        val moved = chunks.removeAt(from)
        chunks.add(to, moved)
        val body = ArrayList<String>()
        for (c in chunks) body.addAll(c)
        removeRange(lines, starts[0], seq.end)
        lines.addAll(starts[0], body)
        return reparse(doc, lines)
    }

    // ---------------------------------------------------------------- 内部

    private fun removeRange(lines: MutableList<String>, start: Int, end: Int) {
        for (i in (end - 1) downTo start) if (i >= 0 && i < lines.size) lines.removeAt(i)
    }

    private fun flowRawItems(doc: YamlDoc, seq: YamlNode): MutableList<String> {
        val line = doc.lines[seq.start]
        return seq.items.mapTo(ArrayList()) {
            line.substring(it.valueStart.coerceAtMost(line.length), it.valueEnd.coerceAtMost(line.length))
        }
    }

    /** 用新的项文本重写 flow 序列：装得下就留在行内，否则整键转块式（每项原文直接做 `- 项`）。 */
    private fun flowSeqRewrite(doc: YamlDoc, seqPath: List<Any>, seq: YamlNode, raws: List<String>): YamlDoc {
        val lines = ArrayList(doc.lines)
        val rendered = "[" + raws.joinToString(", ") + "]"
        val line = lines[seq.start]
        if (rendered.length <= 160 || raws.isEmpty()) {
            lines[seq.start] = line.substring(0, seq.flowStart) + rendered + line.substring(seq.flowEnd.coerceAtMost(line.length))
            return reparse(doc, lines)
        }
        if (seqPath.isEmpty() || seqPath.last() is Int) return doc
        val parent = doc.resolveParent(seqPath.dropLast(1)) ?: return doc
        val entry = doc.findEntry(parent, seqPath.last().toString()) ?: return doc
        val (start, end) = doc.lineBounds(entry)
        val pad = " ".repeat(entry.keyIndent + 2)
        val render = ArrayList<String>()
        render.add(doc.keyPrefix(entry) + YamlValue.renderKey(entry.key) + ":")
        for (r in raws) render.add("$pad- $r")
        removeRange(lines, start, end)
        lines.addAll(start, render)
        return reparse(doc, lines)
    }

    fun flowSet(doc: YamlDoc, parent: YamlNode, key: String, value: Any?): YamlDoc {
        val rendered = YamlValue.flowLeafOf(value)
            ?: return doc     // 装不进一行的集合 / 多行字符串：不动（UI 会提示未改动），绝不写成折叠的引号串
        val lines = ArrayList(doc.lines)
        val line = lines[parent.start]
        val span = line.substring(parent.flowStart, parent.flowEnd.coerceAtMost(line.length))
        val newFlow = flowSetText(span, key, rendered)
        lines[parent.start] = line.substring(0, parent.flowStart) + newFlow +
            line.substring(parent.flowEnd.coerceAtMost(line.length))
        return reparse(doc, lines)
    }

    /** 在 flow 映射文本里设置 key：存在则替换值，不存在则追加。对应 flow_set。 */
    fun flowSetText(text: String, key: String, rendered: String): String {
        val (spans, close) = parseFlowEntries(text, 0)
        for (s in spans) {
            if (s.key == key) {
                return text.substring(0, s.valStart) + rendered + text.substring(s.valEnd)
            }
        }
        val c = close.coerceAtMost(text.length)
        val inner = text.substring(1, (c - 1).coerceAtLeast(1)).trimEnd()
        if (inner.isBlank()) return "{" + YamlValue.renderKeyFlow(key) + ": " + rendered + "}"
        val sep = if (inner.endsWith(",")) "" else ","
        return text.substring(0, c - 1) + sep + " " + YamlValue.renderKeyFlow(key) + ": " + rendered +
            text.substring(c - 1)
    }

    /** 删掉 flow 映射里的 key（连同一个分隔逗号）。对应 flow_remove。 */
    fun flowRemove(text: String, key: String): String {
        val (spans, _) = parseFlowEntries(text, 0)
        val s = spans.firstOrNull { it.key == key } ?: return text
        var start = s.keyStart
        var end = s.valEnd
        var j = end
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        if (j < text.length && text[j] == ',') {
            end = j + 1
            while (end < text.length && text[end] == ' ') end++
        } else {
            var k = start - 1
            while (k >= 0 && (text[k] == ' ' || text[k] == '\t')) k--
            if (k >= 0 && text[k] == ',') start = k
        }
        return text.substring(0, start) + text.substring(end)
    }

    /** flow 映射里改键名：只替换键那一段文本。对应 flow_rename。 */
    fun flowRename(text: String, key: String, newKey: String): String {
        val (spans, _) = parseFlowEntries(text, 0)
        val s = spans.firstOrNull { it.key == key } ?: return text
        return text.substring(0, s.keyStart) + YamlValue.renderKeyFlow(newKey) + text.substring(s.keyEnd)
    }

    private fun replaceEntryBlock(doc: YamlDoc, entry: YamlEntry, value: Any?): YamlDoc {
        val (start, end) = doc.lineBounds(entry)
        val render = YamlValue.renderKeyValue(doc.keyPrefix(entry), entry.key, value, entry.keyIndent, entry.node?.anchor)
        val lines = ArrayList(doc.lines)
        removeRange(lines, start, end)
        lines.addAll(start, render)
        return reparse(doc, lines)
    }

    /** 在 parentPath 下插入 key；父级缺失就按需新建（只建映射）。返回 null 表示路径不可编辑。 */
    private fun insertKey(doc: YamlDoc, parentPath: List<Any>, key: String, value: Any?,
                          asEmpty: Boolean): YamlDoc? {
        var cur = doc
        // 逐级确保父级存在：缺的建成空映射；存在但是序列/标量（不是空值）就不能往下建
        for (i in 1..parentPath.size) {
            val prefix = parentPath.subList(0, i)
            val node = cur.get(prefix)
            if (node == null) {
                val seg = prefix.last()
                if (seg is Int) return null                   // 序列项不凭空新建
                val built = insertKey(cur, prefix.subList(0, prefix.size - 1), seg.toString(), null, asEmpty = true)
                    ?: return null
                cur = built
            } else if (node.kind == YamlNode.Kind.SCALAR && !cur.isNullText(node)) {
                return null                                   // 中间是纯值：不能往下建
            } else if (node.kind == YamlNode.Kind.SEQ && !(i < parentPath.size && parentPath[i] is Int)) {
                return null                                   // 中间是序列：只能按下标走进项里
            }
        }
        val parent = (if (parentPath.isEmpty()) cur.root else cur.resolveParent(parentPath, allowNull = true))
            ?: return null
        if (parent.flow) {
            // 父级是 flow 映射（`- {name: a, …}` 这类项）：没有「行」可插，直接在行内那段文本上加键
            return flowSet(cur, parent, key, if (asEmpty) emptyMap<String, Any?>() else value)
        }
        val where = keyLinePos(cur, parentPath) ?: return null
        val (at, indent) = where
        val lines = ArrayList(cur.lines)
        if (asEmpty) {
            lines.add(at, " ".repeat(indent) + YamlValue.renderKey(key) + ":")
            return reparse(cur, lines)
        }
        val render: List<String> = if (value == null) listOf(" ".repeat(indent) + YamlValue.renderKey(key) + ":")
        else YamlValue.renderKeyValue(" ".repeat(indent), key, value, indent)
        lines.addAll(at, render)
        return reparse(cur, lines)
    }

    /** 插入点：(行号, 缩进)。父级是空值键时插在它后面并多缩进两格。 */
    private fun keyLinePos(doc: YamlDoc, parentPath: List<Any>): Pair<Int, Int>? {
        if (parentPath.isEmpty()) return doc.root.end to 0
        val parent = doc.resolveParent(parentPath, allowNull = true) ?: return null
        if (parent.kind == YamlNode.Kind.MAP && parent.entries.isNotEmpty()) {
            return doc.childEnd(parent) to parent.indent
        }
        if (parent.kind == YamlNode.Kind.MAP) return parent.start to parent.indent
        val lastSeg = parentPath.last()
        if (lastSeg is Int) return null
        val grand = doc.resolveParent(parentPath.dropLast(1), allowNull = true) ?: return null
        val entry = doc.findEntry(grand, lastSeg.toString()) ?: return null
        return (entry.line + 1) to (entry.keyIndent + 2)
    }

    private fun reparse(doc: YamlDoc, lines: List<String>): YamlDoc =
        YamlDoc.parse((if (doc.hasBom) "\uFEFF" else "") + lines.joinToString(doc.eol))

    // ---------------------------------------------------------------- 便捷

    fun apply(text: String, ops: List<Pair<String, Any?>>): String {
        var doc = YamlDoc.parse(text)
        for ((path, value) in ops) doc = setValue(doc, path, value)
        return doc.dump()
    }

    fun remove(text: String, paths: List<String>): String {
        var doc = YamlDoc.parse(text)
        for (p in paths) doc = removeKey(doc, p)
        return doc.dump()
    }
}
