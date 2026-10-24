package top.yukonga.mishka.custom.forms

/**
 * 条目级（一级）/ 字段级（二级）锚点的现状读取与行级手术，对齐 mihomo_box `anchorSection`
 * （pages-flow.js）的语义子集：
 *  - 一级 = 条目键行（映射的 `名字:` / 序列的 `- `）块内的 `<<: *name` 合并继承：换绑 / 清除 / 新挂；
 *  - 二级 = 条目内子字段的 `<<: *name` 合并与 `键: *name` 整体引用：挂 / 换绑 / 摘除；
 *  - `&定义` 动作已下线（与参考实现一致）：存量字段级 `&名` 只允许摘除，不新挂、不改名。
 *
 * 现状全部从 [YamlDoc] 行模型读（`<<` 合并键、alias、anchor 都在模型里标好），手术只重写目标行，
 * 其余行逐字不动；形态拆不动（flow 单行条目、多合并来源）返回 null 由 UI 提示走编辑器。
 */

/** 条目的一级继承现状。[mergeLines] 为 `<<:` 行号（1 基）；[multi] = 多来源，UI 锁定不让改。 */
internal data class EntryMergeState(
    val merges: List<String>,
    val mergeLines: List<Int>,
    val multi: Boolean,
)

/** 条目内一个本地子字段的二级锚点现状。[absent] = 源码没有这个字段的本地行。 */
internal data class FieldAnchorState(
    val key: String,
    val line1: Int,
    val defAnchor: String?,
    val aliasRef: String?,
    val merges: List<String>,
    val multi: Boolean,
    val isMap: Boolean,
    val absent: Boolean = false,
)

/**
 * 全文锚点定义：名字 / 定义行（1 基）/ 是否为映射（`<<:` 只能指向映射）/
 * [ownerKey] = 定义它的 YAML 键名（如 `host-def: &host { … }` 的 `host-def`；序列项等无键场景为 null）。
 */
internal data class AnchorDefInfo(val name: String, val line1: Int, val isMap: Boolean, val ownerKey: String? = null)

internal object AnchorInheritance {

    // ==================== 现状读取 ====================

    fun entryMerges(doc: YamlDoc, base: YPath): EntryMergeState {
        val n = doc.get(base) ?: return EntryMergeState(emptyList(), emptyList(), false)
        val merges = ArrayList<String>()
        val lines = ArrayList<Int>()
        if (n.kind == YamlNode.Kind.MAP || n.kind == YamlNode.Kind.SEQ) {
            for (e in n.entries) {
                if (!e.isMerge) continue
                collectAliases(e.node, merges)
                lines.add(e.line + 1)
            }
        }
        return EntryMergeState(merges, lines, merges.size > 1 || lines.size > 1)
    }

    private fun collectAliases(node: YamlNode?, out: MutableList<String>) {
        when {
            node == null -> Unit
            node.kind == YamlNode.Kind.SEQ -> node.items.forEach { it.alias?.let(out::add) }
            else -> node.alias?.let(out::add)
        }
    }

    /** 条目内本地子字段的二级现状；flow 单行条目拆不动，返回空（UI 提示走一级或文本）。 */
    fun fieldStates(doc: YamlDoc, base: YPath): List<FieldAnchorState> {
        val n = doc.get(base) ?: return emptyList()
        if (n.kind != YamlNode.Kind.MAP || n.flow) return emptyList()
        return n.entries.filter { !it.isMerge }.map { e ->
            val v = e.node
            val merges = ArrayList<String>()
            var multi = false
            // flow 映射（`override: { …, <<: *host }`）的合并键也在 entries 里，照样读
            if (v != null && v.kind == YamlNode.Kind.MAP) {
                val ms = v.entries.filter { it.isMerge }
                ms.forEach { m -> collectAliases(m.node, merges) }
                multi = ms.size > 1
            }
            FieldAnchorState(
                key = e.key,
                line1 = e.line + 1,
                defAnchor = v?.anchor,
                aliasRef = v?.takeIf { it.kind == YamlNode.Kind.RAW && !it.multi }?.alias,
                merges = merges,
                multi = multi,
                isMap = v?.kind == YamlNode.Kind.MAP,
            )
        }
    }

    /** 全文 `&定义` 清单（递归走节点树）：候选列表与「定义在引用行之前」硬规则用它。flow 映射也是映射，`<<:` 可指向它。 */
    fun anchorDefs(doc: YamlDoc): List<AnchorDefInfo> {
        val out = ArrayList<AnchorDefInfo>()
        fun walk(n: YamlNode?, owner: String?) {
            if (n == null) return
            n.anchor?.let { out.add(AnchorDefInfo(it, n.start + 1, n.kind == YamlNode.Kind.MAP, owner)) }
            n.entries.forEach { walk(it.node, if (it.isMerge) owner else it.key) }
            n.items.forEach { walk(it, owner) }
        }
        walk(doc.root, null)
        return out
    }

    // ==================== 行级手术 ====================

    /** 行尾裸注释（含 `#`），无则空串。本地复刻一份，避免 forms 包依赖 anchor 包（离线对拍要单编本包）。 */
    private fun trailingComment(line: String): String {
        var quote: Char? = null
        for (i in line.indices) {
            val c = line[i]
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '#' -> if (i == 0 || line[i - 1].isWhitespace()) return line.substring(i).trimEnd()
            }
        }
        return ""
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

    /** 裸文本掩码：引号内的位置为 false——flow 里找 `<<` 时不能命中引号里的内容。 */
    private fun quoteMask(line: String): BooleanArray {
        val mask = BooleanArray(line.length) { true }
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                mask[i] = false
                if (c == quote) {
                    if (i + 1 < line.length && line[i + 1] == quote) {
                        mask[i + 1] = false
                        i++
                    } else quote = null
                }
            } else if (c == '"' || c == '\'') {
                mask[i] = false
                quote = c
            }
            i++
        }
        return mask
    }

    /** 条目块子行的缩进：首个子键 / 首个序列项的缩进，没有就按表头缩进 + 2。 */
    private fun childIndent(n: YamlNode): Int {
        val head = indentOfLine(n)
        val first = n.entries.firstOrNull()?.keyIndent ?: n.items.firstOrNull()?.indent
        return first ?: head + 2
    }

    /** 表头行（映射键行 / dash 行）的缩进。 */
    private fun indentOfLine(n: YamlNode): Int = n.indent

    /**
     * 表头行下标（0 基）：序列项 = dash 行；flow = 自身行；块映射的 [YamlNode.start] 是第一个子行，
     * 键行在它前一行。
     */
    fun headLine0(n: YamlNode): Int = when {
        n.dash >= 0 -> n.dash
        n.flow -> n.start
        else -> n.start - 1
    }

    private fun MutableList<String>.replaceAt(index: Int, line: String) { this[index] = line }

    /**
     * 一级继承换绑 / 清除 / 新挂。[name] 为 null = 不继承（删 `<<:` 行）。
     * 多来源、flow 条目返回 null（拒绝）。dash 行内联 `<<`（`- <<: *a`）只替换段、不动 dash。
     */
    fun setEntryMerge(doc: YamlDoc, base: YPath, name: String?): String? {
        val n = doc.get(base) ?: return null
        if (n.flow) return null
        val st = entryMerges(doc, base)
        if (st.multi) return null
        val lines = doc.lines.toMutableList()
        if (st.mergeLines.isNotEmpty()) {
            val li = st.mergeLines.first() - 1
            val raw = lines[li]
            val trimmed = raw.trimStart()
            val comment = trailingComment(raw)
            val tail = if (comment.isEmpty()) "" else " " + comment
            if (trimmed.startsWith("<<")) {
                if (name == null) lines.removeAt(li)
                else lines.replaceAt(li, " ".repeat(indentOf(raw)) + "<<: *" + name + tail)
            } else {
                // dash 行内联（`- <<: *a` / `- &x <<: *a`）：只摘 / 换 `<<:` 段
                val re = Regex("<<\\s*:\\s*\\*[^\\s]+")
                if (name == null) {
                    val stripped = re.replace(raw, "").replace(Regex("\\s+$"), "")
                    lines.replaceAt(li, stripped)
                } else {
                    lines.replaceAt(li, re.replace(raw, "<<: *" + name))
                }
            }
            return lines.joinToString("\n")
        }
        if (name == null) return null
        val head = headLine0(n)
        lines.add(head + 1, " ".repeat(childIndent(n)) + "<<: *" + name)
        return lines.joinToString("\n")
    }

    /**
     * 二级整体引用 `键: *name`。[name] 为 null = 清除引用，用 [restore] 回填本地值
     * （展开视图里该字段的生效标量；不是标量时调用方传 null → 拒绝，宁不动）。
     */
    fun setFieldAlias(doc: YamlDoc, base: YPath, key: String, name: String?, restore: String?): String? {
        val n = doc.get(base) ?: return null
        if (n.kind != YamlNode.Kind.MAP || n.flow) return null
        val e = doc.findEntry(n, key)
        val lines = doc.lines.toMutableList()
        val ci = childIndent(n)
        if (e == null) {
            if (name == null) return null
            val head = headLine0(n)
            lines.add(head + 1, " ".repeat(ci) + key + ": *" + name)
            return lines.joinToString("\n")
        }
        val li = e.line
        val raw = lines[li]
        val comment = trailingComment(raw)
        val tail = if (comment.isEmpty()) "" else " " + comment
        val v = e.node
        val blockEnd = v?.end ?: li + 1
        if (name != null) {
            // 块式子行一起删：整段换成 `键: *name`
            repeat(blockEnd - 1 - li) { lines.removeAt(li + 1) }
            lines.replaceAt(li, " ".repeat(e.keyIndent) + key + ": *" + name + tail)
        } else {
            if (restore == null) return null
            repeat(blockEnd - 1 - li) { lines.removeAt(li + 1) }
            lines.replaceAt(li, " ".repeat(e.keyIndent) + key + ": " + restore + tail)
        }
        return lines.joinToString("\n")
    }

    /**
     * 二级合并继承：字段块内挂 / 换绑 / 摘 `<<: *name`。字段不存在时新建 `键:` + `<<:` 两行；
     * 字段是标量 / 别名（合并无意义）返回 null。
     */
    fun setFieldMerge(doc: YamlDoc, base: YPath, key: String, name: String?): String? {
        val n = doc.get(base) ?: return null
        if (n.kind != YamlNode.Kind.MAP || n.flow) return null
        val e = doc.findEntry(n, key)
        val lines = doc.lines.toMutableList()
        val ci = childIndent(n)
        if (e == null) {
            if (name == null) return null
            val head = headLine0(n)
            lines.add(head + 1, " ".repeat(ci) + key + ":")
            lines.add(head + 2, " ".repeat(ci + 2) + "<<: *" + name)
            return lines.joinToString("\n")
        }
        val v = e.node
        val emptyValue = v == null || doc.isNullText(v)
        if (!emptyValue && v?.kind != YamlNode.Kind.MAP) return null
        val merges = ArrayList<String>()
        val mergeLines = ArrayList<Int>()
        if (v != null && v.kind == YamlNode.Kind.MAP) {
            for (m in v.entries) {
                if (!m.isMerge) continue
                collectAliases(m.node, merges)
                mergeLines.add(m.line + 1)
            }
        }
        if (merges.size > 1 || mergeLines.size > 1) return null
        if (v != null && v.flow) {
            // flow 映射（`override: { …, <<: *host }`）：只重写 `<<: *名` 片段，其余 flow 内容逐字不动；
            // 跨多行的 flow 不猜，返回 null 走编辑器
            if (v.start != v.end - 1) return null
            val li = v.start
            val raw = lines[li]
            val mask = quoteMask(raw)
            val re = Regex("<<\\s*:\\s*\\*[^\\s,}]+")
            val match = re.findAll(raw).firstOrNull { mask[it.range.first] }
            if (match != null) {
                if (name == null) {
                    var from = match.range.first
                    var to = match.range.last
                    // 收一个相邻逗号：优先前逗号，没有再后逗号
                    var p = from - 1
                    while (p >= 0 && raw[p] == ' ') p--
                    if (p >= 0 && raw[p] == ',') from = p
                    else {
                        var q = to + 1
                        while (q < raw.length && raw[q] == ' ') q++
                        if (q < raw.length && raw[q] == ',') to = q
                    }
                    lines.replaceAt(li, raw.removeRange(from, to + 1))
                } else {
                    lines.replaceAt(li, raw.replaceRange(match.range, "<<: *" + name))
                }
                return lines.joinToString("\n")
            }
            if (name == null) return null
            // 无合并键：在闭括号前插 `<<: *name`，闭括号前保留一个空格；空 flow 不补逗号
            val close = (v.flowEnd - 1).coerceIn(0, raw.length)
            if (raw.getOrNull(close) != '}') return null
            var b = close - 1
            while (b >= 0 && raw[b] == ' ') b--
            val insert = if (b < 0 || raw[b] == '{') " <<: *" else ", <<: *"
            lines.replaceAt(
                li,
                raw.substring(0, b + 1) + insert + name + " " + raw.substring(close),
            )
            return lines.joinToString("\n")
        }
        if (mergeLines.isNotEmpty()) {
            val li = mergeLines.first() - 1
            val raw = lines[li]
            val comment = trailingComment(raw)
            val tail = if (comment.isEmpty()) "" else " " + comment
            if (name == null) lines.removeAt(li)
            else lines.replaceAt(li, " ".repeat(indentOf(raw)) + "<<: *" + name + tail)
            return lines.joinToString("\n")
        }
        if (name == null) return null
        val fieldIndent = e.keyIndent
        lines.add(e.line + 1, " ".repeat(fieldIndent + 2) + "<<: *" + name)
        return lines.joinToString("\n")
    }

    /** 摘除存量字段级 `&名`（定义动作下线后的唯一出口）：`键: &x 值` → `键: 值`。 */
    fun removeFieldDef(doc: YamlDoc, base: YPath, key: String): String? {
        val n = doc.get(base) ?: return null
        if (n.kind != YamlNode.Kind.MAP || n.flow) return null
        val e = doc.findEntry(n, key) ?: return null
        val v = e.node ?: return null
        val def = v.anchor ?: return null
        val li = e.line
        val lines = doc.lines.toMutableList()
        val raw = lines[li]
        val token = "&" + def
        val at = raw.indexOf(token)
        if (at < 0) return null
        var tailStart = at + token.length
        while (tailStart < raw.length && raw[tailStart] == ' ') tailStart++
        val head = raw.substring(0, at).trimEnd(' ')
        val tail = raw.substring(tailStart)
        lines[li] = if (tail.isEmpty()) head else head + " " + tail
        return lines.joinToString("\n")
    }

    /**
     * 在**单行 flow 映射**里写入一个「字符串列表」字段（如 `override.override-expr`）：引擎拒绝把
     * 装不下一行的集合塞进 flow（[YamlPatch.flowSet] 返回原文），这里做**行内原位替换**（r19，
     * 对齐 mihomo_box `patchAnchorDefBlock` 的范围替换语义：单行 flow 只换目标键值段，形态不变，
     * 不再像 r5 那样展开成块式）——其它键值段与 `<<: *别名` 的原文逐字保留，目标键改写成
     * `键: ['…', '…']`（[items] 为空则删掉该段，即删除字段；键不存在则追加一段），括号留白与
     * 段间分隔符沿用原行风格，行尾注释原样留在行尾。字段不存在且列表为空时原样返回全文；
     * 跨多行 flow / 解析不动的返回 null 交回编辑器。
     */
    fun setFlowMapListField(doc: YamlDoc, mapPath: YPath, key: String, items: List<String>): String? {
        val node = doc.get(mapPath) ?: return null
        if (node.kind != YamlNode.Kind.MAP || !node.flow) return null
        if (node.start != node.end - 1) return null
        val li = node.start
        val raw = doc.lines[li]
        val open = node.flowStart
        val close = node.flowEnd - 1
        if (close < open || raw.getOrNull(close) != '}' || raw.getOrNull(open) != '{') return null
        val body = raw.substring(open + 1, close)
        val padOpen = if (body.startsWith(" ")) " " else ""
        val padClose = if (body.length > 1 && body.endsWith(" ")) " " else ""
        val sep = flowSepOf(body)
        val newList = if (items.isEmpty()) null else "[" + items.joinToString(", ") { quoteSingle(it) } + "]"
        val parts = ArrayList<String>()
        var replaced = false
        for (seg in splitFlowSegments(body)) {
            val t = seg.trim()
            if (t.isEmpty()) continue
            if (flowSegmentKey(t) == key) {
                replaced = true
                if (newList != null) parts.add(key + ": " + newList) // 空列表 = 删掉该段
            } else {
                parts.add(t)
            }
        }
        if (!replaced) {
            if (newList == null) return doc.lines.joinToString("\n") // 无字段可删也无值可写：原样
            parts.add(key + ": " + newList)
        }
        val flowBody = if (parts.isEmpty()) "{}" else "{" + padOpen + parts.joinToString(sep) + padClose + "}"
        val lines = ArrayList(doc.lines)
        lines[li] = raw.substring(0, open) + flowBody + raw.substring(close + 1)
        return lines.joinToString("\n")
    }

    /** flow 体里第一个顶层逗号的分隔符原文（`, ` / `,`，跳过引号内与嵌套括号）；无逗号给默认 `", "`。 */
    private fun flowSepOf(body: String): String {
        var depth = 0
        var quote: Char? = null
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
                ',' -> if (depth == 0) return if (body.getOrNull(i + 1) == ' ') ", " else ","
            }
            i++
        }
        return ", "
    }

    /** 全篇锚点引用计数：`<<: *x` / `键: *x` / 序列项 `- *x` 都算一次（可视化「谁在用这个锚点」）。 */
    fun anchorRefCounts(doc: YamlDoc): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        fun bump(name: String?) { if (name != null) counts[name] = (counts[name] ?: 0) + 1 }
        fun walk(n: YamlNode?) {
            if (n == null) return
            bump(n.alias)
            n.entries.forEach { walk(it.node) }
            n.items.forEach { walk(it) }
        }
        walk(doc.root)
        // 根被包进 __seq__ / __scalar__ 虚拟映射时 root.entries 已覆盖；root 自身的别名也计入
        return counts
    }

    /** flow 体按顶层逗号切段（跳过引号与嵌套括号），各段原文保留。 */
    private fun splitFlowSegments(body: String): List<String> {
        val out = ArrayList<String>()
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
                ',' -> if (depth == 0) { out.add(body.substring(start, i)); start = i + 1 }
            }
            i++
        }
        out.add(body.substring(start))
        return out
    }

    /** flow 段的键名（`键: 值` / `<<: *x`；带引号的键也认）。非键值段返回整段。 */
    private fun flowSegmentKey(seg: String): String {
        var quote: Char? = null
        var depth = 0
        var i = 0
        if (seg.startsWith("\"") || seg.startsWith("'")) {
            quote = seg[0]
            i = 1
            while (i < seg.length) {
                if (seg[i] == quote!!) {
                    if (i + 1 < seg.length && seg[i + 1] == quote) i++ else { quote = null; i++; break }
                }
                i++
            }
            val key = seg.substring(1, (i - 1).coerceAtLeast(1))
            while (i < seg.length && seg[i] == ' ') i++
            return if (i < seg.length && seg[i] == ':') key else seg
        }
        while (i < seg.length) {
            val c = seg[i]
            when {
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
                c == ':' && depth == 0 -> return seg.substring(0, i).trim()
            }
            i++
        }
        return seg
    }

    /** 单引号流式安全串：内部 `'` 翻倍（YAML 单引号转义）。 */
    private fun quoteSingle(s: String): String = "'" + s.replace("'", "''") + "'"

    // ==================== 继承感知写入（写穿锚点 / 物化兜底） ====================
    //
    // 对齐 mihomo_box `dumpConfigKeepLayout`（core.js）的锚点语义：
    //   · 值来自 `<<:` 合并继承时，改动优先写进锚点定义处（&名 所在块），别名 / 合并关系原样保留，
    //     其它引用者同步生效——参考实现对 proxy-providers / rule-providers 的「键本身继承」也写穿
    //     （其源码注释：用户反馈「修改部分参数值(比如自动更新间隔)没有同步到锚点参数」，这类集合
    //     常把公共字段抽成 &锚点，编辑单个集合时期望同步到锚点定义）；其它顶层块的「键本身继承」
    //     按 YAML 合并语义写本地覆写行（显式键优先于合并键），不写穿。
    //   · 路径要「穿过」一个继承来的映射（如 `<<: *providers` 带进来的 health-check 里改 url）时，
    //     一律写穿：本地只建一个子键会按 YAML 合并语义把继承映射整块顶掉，其余继承子键全部静默丢失
    //     ——这就是「修改已继承锚点的配置会全量覆盖到继承锚点的地方」的根源。
    //   · 写穿不可行（锚点侧是装不下的 flow、锚点定义找不到等）时的兜底：把继承来的整块映射
    //     先物化成本地值（兄弟键一个不丢），再写目标键——仍保持语义等价，只是该块转为本地覆写。

    /** 写穿落点：锚点定义侧的路径 + 锚点名（toast 里点名用）。 */
    internal data class WriteThrough(val path: YPath, val anchor: String)

    /** 继承感知写入的落点：结果文档 + 实际写入路径 +（写穿时）锚点名。 */
    internal data class AnchorAwareWrite(val doc: YamlDoc, val path: YPath, val anchor: String?)

    /** 「键本身继承」也写穿锚点的顶层块（对齐参考实现 core.js applySet 的 topKey 分支）。 */
    private val WRITE_THROUGH_TOPS = setOf("proxy-providers", "rule-providers")

    /**
     * 全文 `&定义` → (锚点名, 定义行 1 基, 定义节点路径)。同名锚点重复定义时全部保留，
     * 由 [writeThroughTarget] 按「定义必须在引用行之前」挑最近的一个（YAML 没有前向别名）。
     */
    fun anchorDefPaths(doc: YamlDoc): List<Triple<String, Int, YPath>> {
        val out = ArrayList<Triple<String, Int, YPath>>()
        fun walk(n: YamlNode?, path: YPath) {
            if (n == null) return
            n.anchor?.let { out.add(Triple(it, n.start + 1, path)) }
            n.entries.forEach { e -> if (!e.isMerge) walk(e.node, path + e.key) }
            n.items.forEachIndexed { i, item -> walk(item, path + i) }
        }
        walk(doc.root, emptyList())
        return out
    }

    /**
     * 计算 [path] 这次写入的写穿落点；返回 null = 按普通本地写（含「本地已有行，本地优先」与
     * 「非合集块的键本身继承 → 本地覆写行」两种参考实现语义）。[depth] 防锚点链成环；
     * [chaining] = 正在锚点链里往下追（顶层块名是锚点宿主，不再按用户写入路径做合集门控）。
     */
    fun writeThroughTarget(doc: YamlDoc, path: YPath, depth: Int = 0, chaining: Boolean = false): WriteThrough? {
        if (path.isEmpty() || depth > 3) return null
        if (doc.get(path) != null) return null                    // 本地已有行：本地优先，不碰锚点
        var k = path.size - 1
        while (k > 0 && doc.get(path.subList(0, k)) == null) k--  // 最深本地存在的前缀
        if (k == 0) return null                                   // 连顶层都没有：普通新建
        val prefix = path.subList(0, k)
        val rest = path.subList(k, path.size)
        val parentNode = doc.get(prefix) ?: return null
        if (parentNode.kind != YamlNode.Kind.MAP) return null
        // 「键本身继承」（只差最后一级）：只有代理集合 / 规则集合写穿；其它块写本地覆写行（参考实现语义）
        if (rest.size == 1 && !chaining && WRITE_THROUGH_TOPS.none { it == path[0] }) return null
        val state = entryMerges(doc, prefix)
        if (state.merges.isEmpty()) return null
        val refLine = state.mergeLines.minOrNull() ?: Int.MAX_VALUE
        val defs = anchorDefPaths(doc)
        for (name in state.merges) {
            // 同名多处定义：取引用行之前最近的一个；都不在引用行前（异常文档）就退而取第一个
            val defPath = defs.filter { it.first == name && it.second < refLine }
                .maxByOrNull { it.second }?.third
                ?: defs.firstOrNull { it.first == name }?.third
                ?: continue
            val target = defPath + rest
            val usable = doc.get(target) != null ||
                FormValues.effectiveValue(doc, target) != null ||
                // 锚点侧至少有承载层（如锚点里的 health-check 映射）：新增子键也落锚点，与参考实现
                // 「路径穿过合并进入锚点内部」的解析结果一致（新增键会同步给所有使用者）
                (rest.size > 1 && doc.get(target.dropLast(1)) != null)
            if (!usable) continue
            // 锚点侧自己还是继承来的（锚点链）：继续往下追；追不动就落在 target
            val deeper = if (doc.get(target) == null) writeThroughTarget(doc, target, depth + 1, chaining = true) else null
            return WriteThrough(deeper?.path ?: target, deeper?.anchor ?: name)
        }
        return null
    }

    /**
     * 物化兜底定位：路径上某一级父键本地缺失、但继承视图里是映射时，返回该级路径与完整继承内容
     * （调用方先把整块落成本地值，再写目标键）。继承视图里父级是标量 / 序列时返回 null——
     * 那种路径普通写入也会被引擎拒绝，不需要兜底。
     */
    fun materializeInheritedPrefix(doc: YamlDoc, path: YPath): Pair<YPath, Map<String, Any?>>? {
        for (k in 1 until path.size) {
            val prefix = path.subList(0, k)
            if (doc.get(prefix) != null) continue
            val eff = FormValues.effectiveValue(doc, prefix) ?: continue
            if (eff !is Map<*, *>) return null
            val m = LinkedHashMap<String, Any?>()
            for ((key, v) in eff) if (key is String) m[key] = v
            return if (m.isNotEmpty()) prefix to m else null
        }
        return null
    }

    /**
     * 继承感知的 Set：FormHost.set / batch 与离线对拍（EditorLogicProps.applyBatch）都走这里。
     * 依次尝试 写穿锚点 → 物化继承块后本地写 → 普通按路径写；每步都以引擎接受（返回新文档）为准，
     * 全部被拒时返回原文档（调用方按「未改动」提示）。
     */
    fun applySetAware(doc: YamlDoc, path: YPath, value: Any?): AnchorAwareWrite {
        writeThroughTarget(doc, path)?.let { wt ->
            if (doc.canSet(wt.path)) {
                val out = YamlPatch.setValue(doc, wt.path, value)
                if (out !== doc) return AnchorAwareWrite(out, wt.path, wt.anchor)
            }
            // 锚点侧是单行 flow 映射 + 写入值是字符串列表：引擎 flowSet 不肯把集合塞进
            // 一行、会返回原文（旧行为一路退到「这个位置不能这样写」）——r19 起行内原位替换：
            // 单行 flow 保持单行（其余键与 `<<: *别名` 逐字保留），绝不静默丢改动
            if (value is List<*> && wt.path.isNotEmpty()) {
                val items = value.filterIsInstance<String>()
                if (items.size == value.size) {
                    val parentPath = wt.path.dropLast(1)
                    val parentNode = doc.get(parentPath)
                    if (parentNode != null && parentNode.kind == YamlNode.Kind.MAP && parentNode.flow) {
                        val text = setFlowMapListField(doc, parentPath, wt.path.last().toString(), items)
                        if (text != null && text != doc.dump()) {
                            val parsed = YamlDoc.parse(text)
                            if (parsed != null) return AnchorAwareWrite(parsed, wt.path, wt.anchor)
                        }
                    }
                }
            }
            // 锚点侧不收（flow 一行装不下、路径不可编辑…）：落到物化兜底，绝不静默丢改动
        }
        val mat = materializeInheritedPrefix(doc, path)
        if (mat != null) {
            val (prefix, effMap) = mat
            val grown = YamlPatch.setValue(doc, prefix, effMap)
            if (grown !== doc) {
                val out = YamlPatch.setValue(grown, path, value)
                if (out !== grown) return AnchorAwareWrite(out, path, null)
            }
        }
        return AnchorAwareWrite(YamlPatch.setValue(doc, path, value), path, null)
    }
}
