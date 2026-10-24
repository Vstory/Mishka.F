package top.yukonga.mishka.custom.forms

/** 文档内的一段路径：字符串段走映射键，Int 段走序列下标（与 [YamlDoc.splitPath] 的产物同型）。 */
internal typealias YPath = List<Any>

/**
 * 从文档里读表单需要的值。全部走 [YamlDoc] 的行模型（不改文本、不建副本），
 * 与写回侧的路径语义严格一致——读得到的路径才写得进。
 */
internal data class FormMapListRow(
    val key: String,
    val values: List<String>,
    val isSequence: Boolean,
    val supported: Boolean = true,
)

internal data class FormMapListEdit(
    val source: FormMapListRow?,
    val key: String,
    val values: List<String>,
    val isSequence: Boolean,
)

internal object FormValues {

    /** 行内标量 / 别名 / `|` 块的文本（去引号）；flow 集合给整段原文；块集合给 null。 */
    fun scalarText(doc: YamlDoc, n: YamlNode?): String? {
        if (n == null) return null
        val line = doc.lines[n.start]
        if (n.kind == YamlNode.Kind.MAP || n.kind == YamlNode.Kind.SEQ) {
            return if (n.flow) line.substring(n.flowStart, n.flowEnd.coerceAtMost(line.length)) else null
        }
        if (n.multi) {
            val body = doc.lines.subList((n.start + 1).coerceAtMost(doc.lines.size), n.end.coerceAtMost(doc.lines.size))
            val indent = body.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
            return body.joinToString("\n") { if (it.length >= indent) it.substring(indent) else it.trimStart() }.trimEnd()
        }
        if (n.alias != null) return "*" + n.alias
        val raw = line.substring(
            n.valueStart.coerceAtLeast(0).coerceAtMost(line.length),
            n.valueEnd.coerceAtLeast(0).coerceAtMost(line.length),
        )
        return unquote(raw.trim())
    }

    fun readRaw(doc: YamlDoc, path: YPath): String? = scalarText(doc, doc.get(path))

    /** 参考实现的 hasVal：键存在且不是 null / ~ / 空值。 */
    fun hasValue(doc: YamlDoc, path: YPath): Boolean {
        val n = doc.get(path) ?: return false
        return !doc.isNullText(n)
    }

    fun readBool(doc: YamlDoc, path: YPath): Boolean? = when (readRaw(doc, path)?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
    }

    /** 块序列或 flow 序列里的标量项；不是序列则空。 */
    fun readList(doc: YamlDoc, path: YPath): List<String> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.SEQ) return emptyList()
        return n.items.mapNotNull { item ->
            if (item.kind == YamlNode.Kind.MAP || item.kind == YamlNode.Kind.SEQ) scalarText(doc, item)
            else if (item.inline || item.multi) scalarText(doc, item) else null
        }
    }

    /**
     * 列表字段的当前项：普通列表读序列；带 `join` 的字段（exclude-type 的 `A|B`）在 YAML 里是一个字符串，按分隔符拆开。
     * 字符串写法的普通列表（`network: tcp`）也容忍为单项，便于在表单里看见并改成列表。
     */
    fun readListField(doc: YamlDoc, field: FormField, path: YPath): List<String> {
        val sep = field.join
        if (sep != null) return readRaw(doc, path)?.split(sep)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val n = doc.get(path) ?: return emptyList()
        if (n.kind == YamlNode.Kind.SEQ) return readList(doc, path)
        if (n.kind == YamlNode.Kind.MAP) return emptyList()
        if (doc.isNullText(n)) return emptyList()
        return scalarText(doc, n)?.ifBlank { null }?.let { listOf(it) } ?: emptyList()
    }

    /** 映射的 键 → 值文本（列表值合成逗号分隔）。 */
    fun readMap(doc: YamlDoc, path: YPath): List<Pair<String, String>> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.MAP) return emptyList()
        return n.entries.filter { !it.isMerge }.map { e ->
            val v = e.node
            val text = if (v != null && v.kind == YamlNode.Kind.SEQ) readList(doc, path + e.key).joinToString(", ")
            else scalarText(doc, v).orEmpty()
            e.key to text
        }
    }

    /** 映射列表（`- {pattern: a, target: b}`）→ 每项的 键 → 值文本。 */
    fun readMapList(doc: YamlDoc, path: YPath): List<Map<String, String>> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.SEQ) return emptyList()
        return n.items.mapIndexed { i, item ->
            if (item.kind == YamlNode.Kind.MAP) readMap(doc, path + i).toMap() else emptyMap()
        }
    }

    /**
     * P3 映射列表（hosts / nameserver-policy）：按字段路径读取映射键、值与原始形态。
     * 标量值作为单项返回；序列保留序列标记；嵌套映射 / 别名标记为 unsupported，避免「看起来能编辑」却改写丢结构。
     */
    fun readMapListRows(doc: YamlDoc, path: YPath, splitScalar: Boolean = false): List<FormMapListRow> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.MAP) return emptyList()
        return n.entries.filter { !it.isMerge }.map { entry ->
            val value = entry.node
            when {
                value == null -> FormMapListRow(entry.key, emptyList(), isSequence = false)
                value.kind == YamlNode.Kind.SEQ -> FormMapListRow(
                    entry.key,
                    readList(doc, path + entry.key),
                    isSequence = true,
                    supported = value.items.all { it.kind == YamlNode.Kind.SCALAR && it.alias == null && !it.multi },
                )
                value.kind == YamlNode.Kind.MAP -> FormMapListRow(entry.key, emptyList(), isSequence = false, supported = false)
                value.alias != null || value.kind == YamlNode.Kind.RAW -> FormMapListRow(
                    entry.key,
                    listOfNotNull(scalarText(doc, value)),
                    isSequence = false,
                    supported = false,
                )
                else -> {
                    val raw = scalarText(doc, value).orEmpty()
                    val values = if (splitScalar) raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    else listOf(raw)
                    FormMapListRow(entry.key, values, isSequence = false)
                }
            }
        }
    }

    /** Map field → values in a shape that can be diffed without rewriting unrelated entries. */
    fun readHeaderMap(doc: YamlDoc, path: YPath): Map<String, Any?> {
        val n = doc.get(path) ?: return emptyMap()
        if (n.kind != YamlNode.Kind.MAP) return emptyMap()
        return buildMap {
            for (entry in n.entries.filter { !it.isMerge }) {
                val value = entry.node ?: continue
                put(
                    entry.key,
                    if (value.kind == YamlNode.Kind.SEQ) readList(doc, path + entry.key)
                    else FormValues.scalarText(doc, value).orEmpty(),
                )
            }
        }
    }

    /** Headers map → one editable line per value (HTTP headers may have several candidate values). */
    fun readHeaderRows(doc: YamlDoc, path: YPath): List<Pair<String, String>> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.MAP) return emptyList()
        return buildList {
            for (entry in n.entries.filter { !it.isMerge }) {
                val value = entry.node ?: continue
                if (value.kind == YamlNode.Kind.SEQ) {
                    val items = readList(doc, path + entry.key)
                    if (items.isEmpty()) add(entry.key to "") else items.forEach { add(entry.key to it) }
                } else {
                    add(entry.key to scalarText(doc, value).orEmpty())
                }
            }
        }
    }

    /**
     * 展开视图（别名解开、`<<:` 合并后）里某条路径的值。源码没有本地行、值纯经锚点继承来的，
     * 这里也能读到——可视化「读配置文件参数」走它，本地行优先于它（[readRaw] 等）。
     */
    fun effectiveValue(doc: YamlDoc, path: YPath): Any? {
        var cur: Any? = PlainYaml.toPlain(doc)
        for (seg in path) {
            cur = when {
                seg is Int && cur is List<*> -> cur.getOrNull(seg)
                seg is String && cur is Map<*, *> -> cur[seg]
                else -> return null
            }
        }
        return cur
    }

    /** 展开视图里的标量文本（集合给 null）：继承来的 type / behavior 等判断用它。 */
    fun effectiveText(doc: YamlDoc, path: YPath): String? = when (val v = effectiveValue(doc, path)) {
        null -> null
        is Map<*, *>, is List<*> -> null
        is Boolean -> if (v) "true" else "false"
        else -> v.toString()
    }

    /**
     * 展开视图（别名解开、`<<:` 合并后）里有没有值。「自动补默认值」的判断必须用它而不是
     * [hasValue]：值经锚点继承进来时本地没有对应行，按本地判断会把默认值写成本地覆写，
     * 整块顶掉继承内容（如把继承的 health-check url / interval 盖成官方默认值）。
     */
    fun hasEffective(doc: YamlDoc, path: YPath): Boolean = effectiveValue(doc, path) != null

    /** 展开视图里的布尔（YAML true/false 与字符串写法都认）；本地与继承都没有时 null。 */
    fun effectiveBool(doc: YamlDoc, path: YPath): Boolean? = when (val v = effectiveValue(doc, path)) {
        is Boolean -> v
        is String -> when (v.lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    /** 本地无值时按展开值给摘要（标量照写、布尔映开/关、集合报项数），尾标「继承」；无继承值返回 null。 */
    fun describeEffective(doc: YamlDoc, field: FormField, path: YPath): String? = when (val v = effectiveValue(doc, path)) {
        null -> null
        is Map<*, *> -> "${v.size} 项（继承）"
        is List<*> -> "${v.size} 项（继承）"
        is Boolean -> (if (v) "开" else "关") + "（继承）"
        else -> {
            val s = v.toString()
            val shown = if (field.type == FormFieldType.SELECT) field.options.firstOrNull { it.value == s }?.label ?: s else s
            shown.take(60) + "（继承）"
        }
    }

    /**
     * 读 `override.override-expr`：数组 / 单条字符串都规范成列表。本地没有该行时回落到展开视图
     * （别名解开、`<<:` 合并后的值）——表达式挂在 `&锚点` 里、经 flow 映射 `<<: *host` 带进来的也读得到。
     * 返回「列表 to 是否来自继承」；继承来的值一经编辑就写入本地（成为本条目的本地覆写）。
     */
    fun exprItems(doc: YamlDoc, base: YPath): Pair<List<String>, Boolean> {
        val path = base + "override" + "override-expr"
        val n = doc.get(path)
        // 别名（`override-expr: *x`）本地读只会拿到字面 `*x`，按继承走展开视图
        if (n != null && n.alias == null) {
            val local = if (n.kind == YamlNode.Kind.SEQ) readList(doc, path).filter { it.isNotEmpty() }
            else listOfNotNull(scalarText(doc, n)?.trim()?.ifEmpty { null })
            return local to false
        }
        val items = when (val v = effectiveValue(doc, path)) {
            null -> emptyList()
            is List<*> -> v.mapNotNull { it?.toString()?.trim()?.ifEmpty { null } }
            else -> listOfNotNull(v.toString().trim().ifEmpty { null })
        }
        return items to true
    }

    fun count(doc: YamlDoc, path: YPath): Int {
        val n = doc.get(path) ?: return 0
        return when (n.kind) {
            YamlNode.Kind.MAP -> n.entries.count { !it.isMerge }
            YamlNode.Kind.SEQ -> n.items.size
            else -> 1
        }
    }

    /** 字段当前状态的一行摘要（列表显示 N 项、布尔显示 开/关/未设置）。 */
    fun describe(doc: YamlDoc, field: FormField, path: YPath): String {
        val n = doc.get(path)
        if (n == null || doc.isNullText(n)) return "未设置"
        return when (field.type) {
            FormFieldType.BOOL -> if (readBool(doc, path) == true) "开" else "关"
            FormFieldType.LIST, FormFieldType.NUMLIST, FormFieldType.USERLIST, FormFieldType.PICKLIST,
            FormFieldType.DNSLIST, FormFieldType.APPLIST, FormFieldType.RULESETPICK, FormFieldType.FAKEIPRULE -> {
                val items = readListField(doc, field, path)
                if (items.isEmpty()) "${count(doc, path)} 项" else "${items.size} 项：" + items.joinToString(", ").take(72)
            }
            FormFieldType.SELECT -> readRaw(doc, path)?.let { v ->
                field.options.firstOrNull { it.value == v }?.label
                    ?: if (field.boolKind != null) field.options.firstOrNull { it.value.equals(v, ignoreCase = true) }?.label ?: v else v
            } ?: "未设置"
            FormFieldType.MAPTEXT -> {
                val rows = readMap(doc, path).map { (key, value) -> "$key=$value" }
                if (rows.isEmpty()) "${count(doc, path)} 项" else "${rows.size} 项：${rows.joinToString(" · ").take(72)}"
            }
            FormFieldType.HEADERS -> {
                val rows = readHeaderRows(doc, path).map { (key, value) -> "$key=$value" }
                if (rows.isEmpty()) "${count(doc, path)} 项" else "${rows.size} 项：${rows.joinToString(" · ").take(72)}"
            }
            FormFieldType.MAPLIST -> {
                val rows = if (field.path == "override.proxy-name") {
                    readMapList(doc, path).map { "${it["pattern"].orEmpty()}→${it["target"].orEmpty()}" }
                } else {
                    readMapListRows(doc, path, splitScalar = field.path == "hosts").map { row ->
                        "${row.key}→${if (row.supported) row.values.joinToString(",") else "复杂结构"}"
                    }
                }
                if (rows.isEmpty()) "${count(doc, path)} 项" else "${rows.size} 项：${rows.joinToString(" · ").take(72)}"
            }
            FormFieldType.PASSWORD -> readRaw(doc, path)?.let { if (it.isEmpty()) "" else "•".repeat(it.length.coerceAtMost(12)) }.orEmpty()
            else -> readRaw(doc, path).orEmpty().take(60)
        }
    }

    /** 文本框里的值按 YAML 直觉还原类型：true/false → 布尔，整数 / 小数 → 数字，其余字符串。 */
    fun typedScalar(v: String): Any? = when {
        v == "true" -> true
        v == "false" -> false
        INT_RE.matches(v) -> v.toLongOrNull() ?: v
        FLOAT_RE.matches(v) -> v.toDoubleOrNull() ?: v
        else -> v
    }

    private val INT_RE = Regex("^-?(0|[1-9][0-9]*)$")
    private val FLOAT_RE = Regex("^-?(0|[1-9][0-9]*)\\.[0-9]+$")

    private fun unquote(s: String): String {
        if (s.length >= 2 && (s[0] == '\'' || s[0] == '"') && s.last() == s[0]) {
            val inner = s.substring(1, s.length - 1)
            return if (s[0] == '\'') inner.replace("''", "'") else inner.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return s
    }
}
