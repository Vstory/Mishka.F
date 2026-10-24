package top.yukonga.mishka.custom.anchor

import androidx.compose.runtime.Immutable

/**
 * 定义块的可视化模型：把 `键: &锚点` 带的那一块 YAML 拆成「参数行 / 列表项」，
 * 编辑后仍在**原文行**上做替换（未改动的行逐字保留），不重新 dump 整块。
 *
 * 与 mihomo_box 的差别：那边用 js-yaml 的节点范围做保真替换，Kotlin 侧没有 YAML 依赖，
 * 因此只支持能安全拆解的两种形态——映射（键值对）与序列（逐项）——嵌套多行的值不在这里改，
 * 提示走「文本」模式（整块原文编辑）。这条边界是刻意画的：拆不动的形态宁可让用户看原文，
 * 也不能让面板猜错层级把配置写坏。
 */

@Immutable
enum class DefKind { Map, Seq, Scalar, Unknown }

@Immutable
data class DefEntrySeed(
    val keyRaw: String,
    val value: String,
    val comment: String,
    val indent: String,
    val startIdx: Int,
    val endIdx: Int,
    val nested: Boolean,
    /** 行内 flow 项的原文段（如 `interval: 3600`）：未改动的项写回时逐字保留，保住原有排版。 */
    val rawSeg: String = "",
)

@Immutable
data class DefBlock(
    val name: String,
    val keyDisplay: String,
    val keyRaw: String,
    val indent: Int,
    val dash: Boolean,
    val kind: DefKind,
    val headerValue: String,
    val headerComment: String,
    val entries: List<DefEntrySeed>,
    val lines: List<String>,
    val startLine1: Int,
    val path: String,
    /** 值是单行行内 flow（`键: &名 { … }` / `键: &名 [ … ]`）：可视化按键值拆行显示，写回时仍压成一行。 */
    val flowInline: Boolean = false,
    /** 行内 flow 的原有排版：括号内两侧留白与项间分隔符，未改动项逐字回写时整体复原。 */
    val flowPadOpen: String = "",
    val flowPadClose: String = "",
    val flowSep: String = ", ",
) {
    /** 能否用可视化表单编辑（Scalar 单值也走一个值框，故一并算可视化）。 */
    val visual: Boolean get() = kind != DefKind.Unknown
    val lineCount: Int get() = lines.size
}

/** 一行可编辑内容。[seed] 为 null 表示新增行。[items] 非 null 表示这一行的值是
 * 「块序列单行项」（如 override-expr 的表达式列表），按项编辑、逐行写回。 */
@Immutable
data class DefRow(
    val seed: DefEntrySeed?,
    val key: String,
    val value: String,
    val items: List<SeqItemEdit>? = null,
    /** 非 null 表示这一行的值是嵌套块映射，按展开面板的键值编辑逐支写回（flow 来源在对话框里直接合成 [value]）。 */
    val mapItems: List<FlowSubEdit>? = null,
)

/** 嵌套块序列的一行（如 override-expr 下的每条表达式）：块内行号 + 行正文原文。 */
@Immutable
data class NestedSeqItem(
    val lineIdx: Int,
    val raw: String,
    val indent: String,
    val comment: String,
)

/** 可视化编辑器对嵌套块序列一行的写回意图：[lineIdx] 为 null 表示新增行。 */
@Immutable
data class SeqItemEdit(val lineIdx: Int?, val value: String)

/** 行内 flow 映射值里的一对键值（如 `{ enable: true, … }` 的 `enable`）：键原文 / 值原文 / 整段原文。 */
@Immutable
data class FlowSubItem(val keyRaw: String, val value: String, val rawSeg: String)

/** 嵌套块映射的一支（如 `health-check:` 下的 `enable`）：块内行号 + 值可视化文本 + 缩进/注释。 */
@Immutable
data class NestedMapItem(
    val keyRaw: String,
    val lineIdx: Int,
    val indent: String,
    val comment: String,
    /** 对话框值框里的初值：单行支=行内值原文；子块支=整支的 flow dump。 */
    val viz: String,
)

/** flow 映射值里一对键值的写回意图：[keyRaw] 为 null 表示新增键（由对话框规范化后填入）。 */
@Immutable
data class FlowSubEdit(val keyRaw: String?, val value: String)

/** flow 序列值里一项的写回意图：[idx] 为 null 表示新增项。 */
@Immutable
data class FlowSeqEdit(val idx: Int?, val value: String)

/** 值树节点（对照 mihomo_box 的「展开值编辑器」：值框 + 展开一层）：map / seq / scalar。 */
@Immutable
data class ValNode(val kind: String, val entries: List<ValEntry>, val scalar: String = "") {
    companion object {
        const val MAP = "map"
        const val SEQ = "seq"
        const val SCALAR = "scalar"
    }
}

/** 值树的一支：[key] 为 null 表示序列项。 */
@Immutable
data class ValEntry(val key: String?, val node: ValNode)

object AnchorBlock {

    private val SeqItemRegex = Regex("^(\\s*)-\\s*(.*)$")
    private val MapEntryRegex = Regex("^(\\s*)([^:#][^:]*?)\\s*:\\s*(.*)$")

    fun parse(lines: List<String>, startIndex: Int, endIndex: Int, name: String): DefBlock? {
        val header = lines.getOrNull(startIndex) ?: return null
        val shape = AnchorEdit.parseDefLine(header) ?: return null
        if (shape.name != name) return null
        val path = AnchorScan.pathOf(lines, startIndex)
        val blockLines = lines.subList(startIndex, endIndex + 1).toList()
        val headerComment = AnchorScan.trailingComment(header)
        val bodyStart = 1
        val bodyEnd = blockLines.lastIndex

        val headerValue = AnchorScan.withoutComment(shape.value).trim()
        if (headerValue.isNotEmpty()) {
            // 单行行内 flow（`键: &名 { a: 1, b: 2 }` / `键: &名 [a, b]`）：拆成键值行进可视化，
            // 键跟值分开展示；写回仍压成一行 flow（[render] 里 flowInline 分支）。拆不动才回落标量。
            val flow = parseFlow(headerValue)
            if (flow != null) {
                val (seq, segments, kv, padOpen, padClose, sep) = flow
                val seeds = segments.mapIndexed { i, raw ->
                    val (k, v) = if (seq) "" to raw else kv[i]
                    DefEntrySeed(
                        keyRaw = k, value = v, comment = "", indent = "  ",
                        startIdx = i, endIdx = i, nested = false, rawSeg = raw,
                    )
                }
                return DefBlock(
                    name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
                    kind = if (seq) DefKind.Seq else DefKind.Map, headerValue = headerValue, headerComment = headerComment,
                    entries = seeds, lines = blockLines, startLine1 = startIndex + 1, path = path, flowInline = true,
                    flowPadOpen = padOpen, flowPadClose = padClose, flowSep = sep,
                )
            }
            return DefBlock(
                name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
                kind = DefKind.Scalar, headerValue = headerValue, headerComment = headerComment,
                entries = emptyList(), lines = blockLines, startLine1 = startIndex + 1, path = path,
            )
        }

        val nonBlank = (bodyStart..bodyEnd).filter { blockLines[it].isNotBlank() }
        if (nonBlank.isEmpty()) {
            return DefBlock(
                name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
                kind = DefKind.Scalar, headerValue = "", headerComment = headerComment,
                entries = emptyList(), lines = blockLines, startLine1 = startIndex + 1, path = path,
            )
        }

        val firstBody = blockLines[nonBlank.first()]
        // 缩进后的 flow 根（`k: &u` + 缩进一行的 `{a: 1}`）必须先判定 Unknown：否则 MapEntryRegex 会
        // 把 `{a` 当成键名、把 `1}` 当成值，逐项编辑就成了对着 flow 容器瞎猜。
        val flowRoot = firstBody.trimStart()
        val kind = when {
            SeqItemRegex.matches(firstBody) -> DefKind.Seq
            flowRoot.startsWith("{") || flowRoot.startsWith("[") -> DefKind.Unknown
            MapEntryRegex.matches(firstBody) -> DefKind.Map
            else -> DefKind.Unknown
        }
        if (kind == DefKind.Unknown) {
            return DefBlock(
                name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
                kind = DefKind.Unknown, headerValue = "", headerComment = headerComment,
                entries = emptyList(), lines = blockLines, startLine1 = startIndex + 1, path = path,
            )
        }

        val entries = mutableListOf<DefEntrySeed>()
        var i = bodyStart
        while (i <= bodyEnd) {
            val line = blockLines[i]
            if (line.isBlank() || AnchorScan.indentOf(line) <= shape.indent) {
                i++
                continue
            }
            val m = if (kind == DefKind.Seq) SeqItemRegex.find(line) else MapEntryRegex.find(line)
            if (m == null) {
                i++
                continue
            }
            var end = i
            var j = i + 1
            while (j <= bodyEnd) {
                val next = blockLines[j]
                if (next.isBlank()) {
                    j++
                    continue
                }
                if (AnchorScan.indentOf(next) > AnchorScan.indentOf(line)) {
                    end = j
                    j++
                } else {
                    break
                }
            }
            val rawValue = if (kind == DefKind.Seq) m.groupValues[2].trim() else m.groupValues[3].trim()
            entries += DefEntrySeed(
                keyRaw = if (kind == DefKind.Seq) "" else m.groupValues[2].trim(),
                value = AnchorScan.withoutComment(rawValue).trim(),
                comment = AnchorScan.trailingComment(line),
                indent = m.groupValues[1],
                startIdx = i,
                endIdx = end,
                nested = end > i,
            )
            i = end + 1
        }
        return DefBlock(
            name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
            kind = kind, headerValue = "", headerComment = headerComment,
            entries = entries, lines = blockLines, startLine1 = startIndex + 1, path = path,
        )
    }

    /**
     * 尝试把嵌套行的值解析成「块序列 · 单行项」（典型：override-expr 的表达式列表）。
     * 拆不动（嵌套映射、项带子块、缩进不齐、空项、项里再套序列、中间夹空行）返回 null，
     * 对话框维持「请切文本」的保守提示。
     */
    fun parseNestedSeqItems(block: DefBlock, seed: DefEntrySeed): List<NestedSeqItem>? {
        if (!seed.nested) return null
        val items = mutableListOf<NestedSeqItem>()
        var itemIndent = -1
        var i = seed.startIdx + 1
        while (i <= seed.endIdx) {
            val line = block.lines[i]
            if (line.isBlank()) return null
            val m = SeqItemRegex.find(line) ?: return null
            val indent = m.groupValues[1]
            if (itemIndent < 0) itemIndent = indent.length
            if (indent.length != itemIndent) return null
            val value = AnchorScan.withoutComment(m.groupValues[2]).trim()
            if (value.isEmpty() || value.startsWith("- ")) return null
            items += NestedSeqItem(i, value, indent, AnchorScan.trailingComment(line))
            i++
        }
        if (items.isEmpty()) return null
        return items
    }

    /**
     * 按 [edits] 写回嵌套块序列：未改动的行逐字保留（含缩进与行尾注释）；改过的行按
     * `- 规范化值` 重写（沿用原行缩进与行尾注释）；新增行按原项缩进追加。
     * 任一值规范化失败返回 null，调用方回落逐字保留。
     */
    fun renderNestedSeqItems(block: DefBlock, seed: DefEntrySeed, edits: List<SeqItemEdit>): List<String>? {
        val items = parseNestedSeqItems(block, seed) ?: return null
        val indent = items.firstOrNull()?.indent ?: " ".repeat(seed.indent.length + 2)
        val out = mutableListOf<String>()
        for (edit in edits) {
            val orig = edit.lineIdx?.let { idx -> items.firstOrNull { it.lineIdx == idx } }
            if (orig != null && edit.value == orig.raw) {
                out += block.lines[orig.lineIdx]
                continue
            }
            val value = YamlValue.normalize(edit.value) ?: return null
            val sb = StringBuilder(indent).append("- ").append(value)
            if (orig != null && orig.comment.isNotEmpty()) sb.append(' ').append(orig.comment)
            out += sb.toString()
        }
        return out
    }

    /** 嵌套序列的项被删空：键行收成空序列（映射 `key: []` / 序列项 `- []`），不留悬空 null 值。 */
    fun renderEmptySeqLine(block: DefBlock, seed: DefEntrySeed): String {
        val sb = StringBuilder(seed.indent)
        if (block.kind == DefKind.Seq) sb.append("- []") else sb.append(seed.keyRaw).append(": []")
        if (seed.comment.isNotEmpty()) sb.append(' ').append(seed.comment)
        return sb.toString()
    }

    /** 嵌套映射的支被删空：键行收成空映射（映射 `key: {}` / 序列项 `- {}`），不留悬空 null 值。 */
    fun renderEmptyMapLine(block: DefBlock, seed: DefEntrySeed): String {
        val sb = StringBuilder(seed.indent)
        if (block.kind == DefKind.Seq) sb.append("- {}") else sb.append(seed.keyRaw).append(": {}")
        if (seed.comment.isNotEmpty()) sb.append(' ').append(seed.comment)
        return sb.toString()
    }

    /**
     * 把单行行内 flow 映射的值（如 `health-check: { enable: true, url: … }` 的值）再拆一层键值。
     * 序列、非 flow、段拆不动返回 null——对话框维持单个值框。
     */
    fun flowMapEntries(value: String): List<FlowSubItem>? {
        val flow = parseFlow(value.trim()) ?: return null
        if (flow.seq) return null
        return flow.segments.mapIndexed { i, raw -> FlowSubItem(flow.kv[i].first, flow.kv[i].second, raw) }
    }

    /** 把单行行内 flow 序列的值拆成各项原文；映射、非 flow、段拆不动返回 null。 */
    fun flowSeqItems(value: String): List<String>? {
        val flow = parseFlow(value.trim()) ?: return null
        if (!flow.seq) return null
        return flow.segments
    }

    /**
     * 按 [edits] 把 flow 映射值重组成一行：未改动的键值用原文段逐字回写（保住内部排版），
     * 改过的按 `键: 规范化值` 重写，新键追加在末尾；括号留白与项间分隔符沿用原文风格。
     * 任一值规范化失败返回 null。
     */
    fun renderFlowMap(original: String, edits: List<FlowSubEdit>): String? {
        val flow = parseFlow(original.trim()) ?: return null
        if (flow.seq) return null
        if (edits.isEmpty() && flow.segments.isEmpty()) return original
        val parts = ArrayList<String>(edits.size)
        for (edit in edits) {
            val key = edit.keyRaw ?: return null
            val idx = flow.kv.indexOfFirst { it.first == key }
            if (idx >= 0 && edit.value == flow.kv[idx].second) {
                parts += flow.segments[idx]
            } else {
                val v = YamlValue.normalize(edit.value) ?: return null
                parts += if (v.isEmpty()) "$key:" else "$key: $v"
            }
        }
        return "{" + flow.padOpen + parts.joinToString(flow.sep) + flow.padClose + "}"
    }

    /**
     * 把一段值文本解析成值树（对照 mihomo_box 的 yValText/jsyaml.load）：flow 映射/序列递归展开，
     * 其余按标量保留原文。空文本 = null 标量。
     */
    fun parseValueText(text: String): ValNode {
        val t = text.trim()
        val flow = parseFlow(t)
        if (flow != null) {
            val entries = flow.kv.map { (k, v) -> ValEntry(if (flow.seq) null else k, parseValueText(v)) }
            return ValNode(if (flow.seq) ValNode.SEQ else ValNode.MAP, entries)
        }
        return ValNode(ValNode.SCALAR, emptyList(), t)
    }

    /**
     * 把一个定义条目的值解析成值树：嵌套块按行递归展开；单行值走 [parseValueText]。
     * 嵌套行形态不齐（缩进漂移、项套项、块里夹 flow 根之外的怪行）返回 null——对话框维持「请切文本」。
     */
    fun parseValueTree(block: DefBlock, seed: DefEntrySeed): ValNode? {
        if (!seed.nested) return parseValueText(seed.value)
        return parseLines(block.lines, seed.startIdx + 1, seed.endIdx)
    }

    /** 递归解析块行区间 [from..to] 为值树；拆不动返回 null。 */
    private fun parseLines(lines: List<String>, from: Int, to: Int): ValNode? {
        var i = from
        while (i <= to && lines[i].isBlank()) i++
        if (i > to) return null
        val seq = SeqItemRegex.find(lines[i]) != null
        val map = if (seq) false else MapEntryRegex.find(lines[i]) != null
        if (!seq && !map) return null
        val entries = parseEntries(lines, i, to, seq) ?: return null
        return ValNode(if (seq) ValNode.SEQ else ValNode.MAP, entries)
    }

    /** 解析同层条目（seq 项 / map 支），子块递归；层缩进漂移或形态不齐返回 null。 */
    private fun parseEntries(lines: List<String>, from: Int, to: Int, seq: Boolean): List<ValEntry>? {
        val out = mutableListOf<ValEntry>()
        var i = from
        var level = -1
        while (i <= to) {
            val line = lines[i]
            if (line.isBlank()) return null
            val indent = AnchorScan.indentOf(line)
            if (level < 0) level = indent
            if (indent != level) return null
            val m = (if (seq) SeqItemRegex.find(line) else MapEntryRegex.find(line)) ?: return null
            var end = i
            var j = i + 1
            while (j <= to) {
                val next = lines[j]
                if (next.isBlank()) return null
                val ni = AnchorScan.indentOf(next)
                if (ni > level) { end = j; j++ } else break
            }
            val raw = AnchorScan.withoutComment(if (seq) m.groupValues[2] else m.groupValues[3]).trim()
            val node = if (end > i) {
                if (raw.isNotEmpty()) return null // `- k: v` 又带子块 / `k: v` 又带子块：形态不猜
                parseLines(lines, i + 1, end) ?: return null
            } else {
                if (seq && raw.isEmpty()) return null
                parseValueText(raw)
            }
            out += ValEntry(if (seq) null else m.groupValues[2].trim(), node)
            i = end + 1
        }
        if (out.isEmpty()) return null
        return out
    }

    /** 值树 → 单行 flow 文本（对照 mihomo_box 的 yValText：lineWidth:-1 + flowLevel:0）。 */
    fun dumpFlow(node: ValNode): String = when (node.kind) {
        ValNode.MAP ->
            if (node.entries.isEmpty()) "{}"
            else node.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${dumpFlow(v)}" }
        ValNode.SEQ ->
            if (node.entries.isEmpty()) "[]"
            else node.entries.joinToString(", ", "[", "]") { (_, v) -> dumpFlow(v) }
        else -> {
            val s = node.scalar
            YamlValue.normalize(s) ?: "'" + s.replace("'", "''") + "'"
        }
    }

    /**
     * 尝试把嵌套行的值解析成「块映射 · 单层支」（典型：health-check 的块式子映射）。
     * 拆不动（缩进不齐、空行、非映射行、支带子块以外的怪形态）返回 null。
     */
    fun parseNestedMapEntries(block: DefBlock, seed: DefEntrySeed): List<NestedMapItem>? {
        if (!seed.nested) return null
        val items = mutableListOf<NestedMapItem>()
        var entryIndent = -1
        var i = seed.startIdx + 1
        while (i <= seed.endIdx) {
            val line = block.lines[i]
            if (line.isBlank()) return null
            val m = MapEntryRegex.find(line) ?: return null
            val indent = m.groupValues[1]
            if (entryIndent < 0) entryIndent = indent.length
            if (indent.length != entryIndent) return null
            var end = i
            var j = i + 1
            while (j <= seed.endIdx) {
                val next = block.lines[j]
                if (next.isBlank()) return null
                if (AnchorScan.indentOf(next) > entryIndent) { end = j; j++ } else break
            }
            val valueRaw = AnchorScan.withoutComment(m.groupValues[3]).trim()
            val viz = if (end > i) {
                if (valueRaw.isNotEmpty()) return null
                val sub = parseLines(block.lines, i + 1, end) ?: return null
                dumpFlow(sub)
            } else {
                valueRaw
            }
            items += NestedMapItem(m.groupValues[2].trim(), i, indent, AnchorScan.trailingComment(line), viz)
            i = end + 1
        }
        if (items.isEmpty()) return null
        return items
    }

    /**
     * 按 [edits] 写回嵌套块映射：未改动的支逐字保留（含缩进与行尾注释），改过的按
     * `键: 规范化值` 重写（子块支被改成单行文本时整支收成一行），新增支按原缩进追加。
     * 任一值规范化失败或键对不上返回 null，调用方回落。
     */
    fun renderNestedMapEntries(block: DefBlock, seed: DefEntrySeed, edits: List<FlowSubEdit>): List<String>? {
        val items = parseNestedMapEntries(block, seed) ?: return null
        val indent = items.first().indent
        val out = mutableListOf<String>()
        val used = HashSet<Int>()
        for (edit in edits) {
            val key = edit.keyRaw ?: return null
            val idx = items.indexOfFirst { it.keyRaw == key }
            if (idx >= 0 && !used.add(idx)) return null
            if (idx < 0) {
                // 新增支：按原缩进追加
                val value = YamlValue.normalize(edit.value) ?: return null
                val sb = StringBuilder(indent).append(key).append(':')
                if (value.isNotEmpty()) sb.append(' ').append(value)
                out += sb.toString()
                continue
            }
            val orig = items[idx]
            if (edit.value == orig.viz) {
                var end = orig.lineIdx
                var j = orig.lineIdx + 1
                while (j <= seed.endIdx) {
                    val next = block.lines[j]
                    if (next.isBlank()) break
                    if (AnchorScan.indentOf(next) > indent.length) { end = j; j++ } else break
                }
                for (k in orig.lineIdx..end) out += block.lines[k]
                continue
            }
            val value = YamlValue.normalize(edit.value) ?: return null
            val sb = StringBuilder(indent).append(key).append(':')
            if (value.isNotEmpty()) sb.append(' ').append(value)
            if (orig.comment.isNotEmpty()) sb.append(' ').append(orig.comment)
            out += sb.toString()
        }
        if (out.isEmpty()) return emptyList()
        return out
    }

    /**
     * 按 [edits] 把 flow 序列值重组成一行：未改动的项用原文段逐字回写，改过的按规范化值重写，
     * 新增项追加在末尾；括号留白与项间分隔符沿用原文风格。任一值规范化失败返回 null。
     */
    fun renderFlowSeq(original: String, edits: List<FlowSeqEdit>): String? {
        val flow = parseFlow(original.trim()) ?: return null
        if (!flow.seq) return null
        if (edits.isEmpty() && flow.segments.isEmpty()) return original
        val parts = ArrayList<String>(edits.size)
        for (edit in edits) {
            val idx = edit.idx
            if (idx != null && idx < flow.segments.size && edit.value == flow.segments[idx]) {
                parts += flow.segments[idx]
            } else {
                parts += YamlValue.normalize(edit.value) ?: return null
            }
        }
        return "[" + flow.padOpen + parts.joinToString(flow.sep) + flow.padClose + "]"
    }

    /**
     * 按 [rows] 生成新的块文本：改过值的行整行重写（保留行尾注释），删掉的行连同其嵌套子行一起消失，
     * 其余行逐字保留，新增行追加在块尾。
     */
    fun render(block: DefBlock, rows: List<DefRow>): String {
        if (block.flowInline) {
            // 行内 flow 压回一行：未改动的项用解析时记下的原文段逐字回写（保住原有排版），
            // 改过 / 新增的项按 `键: 值` 重写；括号留白与项间分隔符沿用原文风格。
            val parts = rows.map { row ->
                val seed = row.seed
                when {
                    seed != null && seed.rawSeg.isNotEmpty() && row.key == seed.keyRaw && row.value == seed.value -> seed.rawSeg
                    block.kind == DefKind.Seq -> row.value
                    else -> if (row.value.isEmpty()) row.key + ":" else row.key + ": " + row.value
                }
            }
            val open = if (block.kind == DefKind.Seq) "[" else "{"
            val close = if (block.kind == DefKind.Seq) "]" else "}"
            val body = open + block.flowPadOpen + parts.joinToString(block.flowSep) + block.flowPadClose + close
            return renderHeader(block, body)
        }
        if (block.kind == DefKind.Scalar) {
            val value = rows.firstOrNull()?.value.orEmpty()
            return renderHeader(block, value)
        }
        if (block.kind == DefKind.Unknown) return block.lines.joinToString("\n")

        val out = ArrayList<String>(block.lines.size + 4)
        out += block.lines[0]
        var idx = 1
        while (idx < block.lines.size) {
            val seed = block.entries.firstOrNull { it.startIdx == idx }
            if (seed == null) {
                out += block.lines[idx]
                idx++
                continue
            }
            val row = rows.firstOrNull { it.seed === seed }
            when {
                row == null -> Unit // 删除：连同 startIdx..endIdx 一起跳过
                row.items != null -> {
                    if (row.items.isEmpty()) {
                        out += renderEmptySeqLine(block, seed)
                    } else {
                        val renderedItems = renderNestedSeqItems(block, seed, row.items)
                        if (renderedItems == null) {
                            out.addAll(block.lines.subList(seed.startIdx, seed.endIdx + 1))
                        } else {
                            out += block.lines[seed.startIdx]
                            out += renderedItems
                        }
                    }
                }
                row.mapItems != null -> {
                    val renderedMap = renderNestedMapEntries(block, seed, row.mapItems)
                    when {
                        renderedMap == null -> out.addAll(block.lines.subList(seed.startIdx, seed.endIdx + 1))
                        renderedMap.isEmpty() -> out += renderEmptyMapLine(block, seed)
                        else -> {
                            out += block.lines[seed.startIdx]
                            out += renderedMap
                        }
                    }
                }
                seed.nested || row.value == seed.value -> out.addAll(block.lines.subList(seed.startIdx, seed.endIdx + 1))
                else -> out += renderEntry(block, seed.keyRaw, row.value, seed.comment, seed.indent)
            }
            idx = seed.endIdx + 1
        }
        rows.filter { it.seed == null }.forEach { row ->
            out += renderEntry(block, row.key, row.value, "", defaultIndent(block))
        }
        return out.joinToString("\n")
    }

    private fun renderHeader(block: DefBlock, value: String): String {
        val sb = StringBuilder()
        sb.append(" ".repeat(block.indent))
        if (block.dash) sb.append("- ")
        sb.append(block.keyRaw).append(": &").append(block.name)
        if (value.isNotEmpty()) sb.append(' ').append(value)
        if (block.headerComment.isNotEmpty()) sb.append(' ').append(block.headerComment)
        return sb.toString()
    }

    private fun renderEntry(block: DefBlock, keyRaw: String, value: String, comment: String, indent: String): String {
        val sb = StringBuilder()
        sb.append(indent)
        if (block.kind == DefKind.Seq) {
            sb.append("-")
            if (value.isNotEmpty()) sb.append(' ').append(value)
        } else {
            sb.append(keyRaw).append(':')
            if (value.isNotEmpty()) sb.append(' ').append(value)
        }
        if (comment.isNotEmpty()) sb.append(' ').append(comment)
        return sb.toString()
    }

    private fun defaultIndent(block: DefBlock): String {
        val existing = block.entries.firstOrNull { it.indent.length > block.indent }?.indent
        return existing ?: " ".repeat(block.indent + 2)
    }

    // ==================== 单行 flow 拆解（键值分离显示用） ====================

    /** 拆解结果：[seq] 是否序列；[segments] 各项原文（trim 后）；[kv] 映射项的键值；后三项是原有排版。 */
    private data class FlowParts(
        val seq: Boolean,
        val segments: List<String>,
        val kv: List<Pair<String, String>>,
        val padOpen: String,
        val padClose: String,
        val sep: String,
    )

    /** 单行 flow 值拆项；`{…}` 里任何一段不像 `键: 值` 返回 null（回落标量显示）。 */
    private fun parseFlow(body: String): FlowParts? {
        val seq = body.startsWith("[")
        if (!seq && !body.startsWith("{")) return null
        val closeCh = if (seq) ']' else '}'
        if (!body.endsWith(closeCh)) return null
        val rawInner = body.substring(1, body.length - 1)
        val padOpen = if (rawInner.startsWith(" ")) " " else ""
        val padClose = if (rawInner.length > 1 && rawInner.endsWith(" ")) " " else ""
        val inner = rawInner.trim()
        if (inner.isEmpty()) return FlowParts(seq, emptyList(), emptyList(), padOpen, padClose, ", ")
        val (segmentsRaw, seps) = splitTopLevel(inner)
        val segments = segmentsRaw.map { it.trim() }.filter { it.isNotEmpty() }
        if (segmentsRaw.size != segments.size) return null // 段里有全空或怪形态，别猜
        val sep = seps.firstOrNull() ?: ", "
        val kv = if (seq) {
            segments.map { "" to it }
        } else {
            val out = ArrayList<Pair<String, String>>(segments.size)
            for (t in segments) {
                val pair = splitKeyValue(t) ?: return null
                out += pair
            }
            out
        }
        return FlowParts(seq, segments, kv, padOpen, padClose, sep)
    }

    /** 顶层逗号切段（跳过引号内与嵌套 `{}` / `[]`），并记下段间分隔符原文（`, ` / `,`…）。 */
    private fun splitTopLevel(body: String): Pair<List<String>, List<String>> {
        val out = ArrayList<String>()
        val seps = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (quote != null) {
                if (c == quote) {
                    if (i + 1 < body.length && body[i + 1] == quote) i++ else quote = null
                }
            } else when (c) {
                '"', '\'' -> quote = c
                '{', '[' -> depth++
                '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    out.add(body.substring(start, i))
                    var j = i + 1
                    while (j < body.length && body[j] == ' ') j++
                    seps.add(body.substring(i, j))
                    start = j
                    i = j
                    continue
                }
            }
            i++
        }
        out.add(body.substring(start))
        return out to seps
    }

    /** `键: 值` 切分（键可带引号）；值里的嵌套 flow 整体保留。不像键值对返回 null。 */
    private fun splitKeyValue(seg: String): Pair<String, String>? {
        var i = 0
        if (seg.startsWith("\"") || seg.startsWith("'")) {
            val quote = seg[0]
            i = 1
            while (i < seg.length) {
                if (seg[i] == quote) {
                    if (i + 1 < seg.length && seg[i + 1] == quote) i++ else { i++; break }
                }
                i++
            }
            while (i < seg.length && seg[i] == ' ') i++
            if (i >= seg.length || seg[i] != ':') return null
            val key = seg.substring(0, i)
            return key to seg.substring(i + 1).trim()
        }
        var depth = 0
        while (i < seg.length) {
            when (val c = seg[i]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                ':' -> if (depth == 0 && (i == seg.lastIndex || seg[i + 1] == ' ' || seg[i + 1] == '\t')) {
                    return seg.substring(0, i).trim() to seg.substring(i + 1).trim()
                }
            }
            i++
        }
        return null
    }

    /**
     * 新建顶层定义块：内容少且不长时压成一行 flow（mihomo_box 的 160 字符规则），
     * 否则退回块式——两者对 mihomo 等价，前者在人读的配置里更省行。
     */
    fun buildTopBlock(topKey: String, anchor: String, rows: List<DefRow>): String {
        val pairs = rows.map { it.key to it.value }
        val inline = pairs.joinToString(", ") { (key, value) -> if (value.isEmpty()) "$key:" else "$key: $value" }
        val oneLine = "$topKey: &$anchor {$inline}"
        if (oneLine.length <= 160 && pairs.isNotEmpty()) return oneLine
        val sb = StringBuilder()
        sb.append(topKey).append(": &").append(anchor)
        pairs.forEach { (key, value) ->
            sb.append('\n').append("  ").append(key).append(':')
            if (value.isNotEmpty()) sb.append(' ').append(value)
        }
        return sb.toString()
    }
}
