package top.yukonga.mishka.custom.anchor

import androidx.compose.runtime.Immutable

/**
 * YAML 锚点扫描：mihomo_box「工具页 → 锚点面板」里 `scanAnchorGraph` 的等价实现。
 *
 * 结论全部来自**原始文本**，不引 YAML 解析库：`&定义` / `*引用` / `<<:` 合并继承在
 * 任何解析器展开之后都不再存在（展开后只剩值），要看锚点本身就只能扫原文。
 *
 * 「裸文本」= 不在引号内、不在 `#` 注释之后的字符区间：`"&x"` 与 `# &x` 都不算锚点语法。
 * 这一约定与 mihomo_box 的 `bareMask` 完全一致——`&` / `*` 在 mihomo 配置里大量出现在
 * 正则、URL 与 proxy 名里，误判会直接把用户的配置改坏。
 *
 * 行号一律 **1 基**（面板显示与「定位到行」直接用它），下标一律 0 基。
 */

@Immutable
data class AnchorDefLoc(val line: Int, val path: String, val text: String)

@Immutable
data class AnchorRefLoc(val line: Int, val path: String, val merge: Boolean, val text: String)

@Immutable
data class AnchorInfo(
    val name: String,
    val defs: List<AnchorDefLoc>,
    val refs: List<AnchorRefLoc>,
) {
    val mergeCount: Int get() = refs.count { it.merge }
    val aliasCount: Int get() = refs.size - mergeCount
    val primaryDef: AnchorDefLoc? get() = defs.firstOrNull()
}

/** 有引用、没有对应 `&定义` 的悬空别名。mihomo 会直接报 unidentified alias，必须能看见。 */
@Immutable
data class DanglingAnchor(val name: String, val refs: List<AnchorRefLoc>)

@Immutable
data class AnchorGraph(
    val anchors: List<AnchorInfo> = emptyList(),
    val danglers: List<DanglingAnchor> = emptyList(),
) {
    val isEmpty: Boolean get() = anchors.isEmpty() && danglers.isEmpty()
    val names: List<String> get() = anchors.map { it.name }
}

@Immutable
data class LineTokens(val defs: List<String>, val refs: List<String>) {
    val isEmpty: Boolean get() = defs.isEmpty() && refs.isEmpty()
}

object AnchorScan {

    /** 锚点名合法性：与 mihomo_box 的 `ANCHOR_NAME_OK` 同规则（YAML 锚点也比这更宽，收紧更安全）。 */
    private val NameRegex = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$")

    private val DefTokenRegex = Regex("&([^\\s,\\[\\]{}#]+)")
    private val RefTokenRegex = Regex("\\*([^\\s,\\[\\]{}#]+)")
    private val MergeLineRegex = Regex("^\\s*<<\\s*:")
    private val NameKeyRegex = Regex("^\\s*(?:-\\s+)?(?:name|\"name\"|'name')\\s*:\\s*([^\\s,}]+)")
    private val KeyRegex = Regex("^\\s*(?:-\\s+)?(?:&\\S+\\s+)?([^:#]+?)\\s*:(?:\\s|$)")

    fun isValidName(name: String): Boolean = NameRegex.matches(name)

    fun isMergeLine(line: String): Boolean = MergeLineRegex.containsMatchIn(line)

    /** 前导空白宽度。制表符按 1 个字符算——与 mihomo_box 的 `^\s*` 长度一致，YAML 本身也禁用 tab 缩进。 */
    fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

    /**
     * 裸文本掩码：true = 该位置的 `&` / `*` / `#` 具有语法意义。
     * 引号内的成对引号（`''` / `""`）算作转义、连续跳过；`#` 之后整行不再是裸文本。
     */
    fun bareMask(line: String): BooleanArray {
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
                        i += 2
                        continue
                    }
                    quote = null
                }
            } else when (c) {
                '"', '\'' -> {
                    mask[i] = false
                    quote = c
                }

                '#' -> {
                    for (j in i until line.length) mask[j] = false
                    break
                }
            }
            i++
        }
        return mask
    }

    /** 行内的 `&定义` / `*引用` 名（裸文本限定）。 */
    fun tokensOnLine(line: String): LineTokens {
        if (line.indexOf('&') < 0 && line.indexOf('*') < 0) return LineTokens(emptyList(), emptyList())
        val mask = bareMask(line)
        val defs = DefTokenRegex.findAll(line)
            .filter { it.range.first < mask.size && mask[it.range.first] }
            .map { it.groupValues[1] }
            .toList()
        val refs = RefTokenRegex.findAll(line)
            .filter { it.range.first < mask.size && mask[it.range.first] }
            .map { it.groupValues[1] }
            .toList()
        return LineTokens(defs, refs)
    }

    fun scan(text: String): AnchorGraph {
        val lines = text.split('\n')
        val defs = LinkedHashMap<String, MutableList<AnchorDefLoc>>()
        val refs = LinkedHashMap<String, MutableList<AnchorRefLoc>>()
        for (index in lines.indices) {
            val line = lines[index]
            val tokens = tokensOnLine(line)
            if (tokens.isEmpty) continue
            // 路径是重活（每行回溯最多 4 层）：只为真的带锚点的行算
            val path = pathOf(lines, index)
            val merge = isMergeLine(line)
            tokens.defs.forEach { name ->
                defs.getOrPut(name) { mutableListOf() } += AnchorDefLoc(index + 1, path, line)
            }
            tokens.refs.forEach { name ->
                refs.getOrPut(name) { mutableListOf() } += AnchorRefLoc(index + 1, path, merge, line)
            }
        }
        val anchors = defs.map { (name, locations) ->
            AnchorInfo(name, locations.toList(), refs[name]?.toList().orEmpty())
        }
        val danglers = refs.entries
            .filter { !defs.containsKey(it.key) }
            .map { DanglingAnchor(it.key, it.value.toList()) }
        return AnchorGraph(anchors, danglers)
    }

    /**
     * 锚点定义块的结束行（0 基，含）。缩进语义：定义行 + 所有缩进更深的行；
     * 序列项从 `- ` 那行起算，`- ` 与键行缩进不同也照收（以定义行自身缩进为基准）。
     */
    fun blockEndIndex(lines: List<String>, startIndex: Int, name: String? = null): Int {
        val base = indentOf(lines[startIndex])
        var end = startIndex + 1
        while (end < lines.size) {
            val line = lines[end]
            if (line.isNotBlank() && indentOf(line) <= base) break
            end++
        }
        if (name != null) end = flowExtend(lines, startIndex, end)
        while (end > startIndex + 1 && lines[end - 1].isBlank()) end--
        return end - 1
    }

    /**
     * 跨行 flow 的闭括号可以与定义行同级（`&x {\n  a: 1\n}`），纯缩进判定会漏掉闭括号、把
     * 后面的配置键一起吞进块里。按裸文本括号平衡往后补足；到文件尾仍未闭合时**不**扩到底
     * （宁可少删也不把整份配置当块删掉）。
     */
    private fun flowExtend(lines: List<String>, startIndex: Int, end0: Int): Int {
        var balance = 0
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i]
            val mask = bareMask(line)
            for (k in line.indices) {
                if (!mask[k]) continue
                when (line[k]) {
                    '{', '[' -> balance++
                    '}', ']' -> balance--
                }
            }
            if (balance <= 0 && i >= end0 - 1) break
            i++
        }
        if (i >= lines.size && balance > 0) return end0
        return maxOf(end0, minOf(i + 1, lines.size))
    }

    /**
     * 上下文路径（面板上每条定义/引用的「在哪」）。逐行回溯：更深的 `name:` 值算一层，
     * 更浅的 `键:` 算一层，最多 4 层——与 mihomo_box 的 `ctxOf` 同算法，输出可直接对照。
     */
    fun pathOf(lines: List<String>, index: Int): String {
        val stack = ArrayDeque<String>()
        var want = indentOf(lines[index])
        for (j in index downTo 0) {
            if (stack.size >= 4) break
            val line = lines[j]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val ind = indentOf(line)
            if (j != index) {
                val nameMatch = NameKeyRegex.find(line)
                if (nameMatch != null && ind >= want) {
                    stack.addFirst(unquote(nameMatch.groupValues[1].trimEnd(',', '}', ']')))
                    want = ind
                    continue
                }
            }
            val keyMatch = KeyRegex.find(line)
            if (keyMatch != null && (ind < want || j == index)) {
                stack.addFirst(unquote(keyMatch.groupValues[1].trim()))
                want = minOf(want, ind)
                if (ind == 0) break
            }
        }
        return stack.joinToString(" → ").ifEmpty { "(顶层)" }
    }

    private fun unquote(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'").trim()

    /**
     * 注释起点下标（0 基），没有注释返回 -1。只在裸文本区判定，引号里的 `#` 不算。
     * 注意不能用 [bareMask] 直接查 `#` 位：掩码里注释区间（含 `#` 自身）整段为 false，
     * 那样永远查不到——这里按「行首或空白后、且不在引号内」单独走一遍。
     */
    fun commentIndex(line: String): Int {
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == quote) {
                    if (i + 1 < line.length && line[i + 1] == quote) i++ else quote = null
                }
            } else when (c) {
                '"', '\'' -> quote = c
                '#' -> if (i == 0 || line[i - 1].isWhitespace()) return i
            }
            i++
        }
        return -1
    }

    /** 行尾注释（含 `#` 到行尾，已 trimEnd），无则空串。 */
    fun trailingComment(line: String): String {
        val at = commentIndex(line)
        return if (at < 0) "" else line.substring(at).trimEnd()
    }

    /** 去掉行尾注释后的行内容（保留结尾空格，调用方按需 trim）。 */
    fun withoutComment(line: String): String {
        val at = commentIndex(line)
        return if (at < 0) line else line.substring(0, at)
    }
}
