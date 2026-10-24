package top.yukonga.mishka.custom.forms

/**
 * 配置内的引用关系（纯 Kotlin，无 Compose 依赖，kotlinc 可直接编译对拍）。
 *
 * 1. [PlainYaml]：把 [YamlDoc] 的行模型展开成普通的 Map / List / 标量树（别名按锚点解开、`<<:` 合并键按 js-yaml
 *    语义并入），让下面两个算法能照参考实现的 JS 逐行移植。
 * 2. [ConfigRefs]：参考实现 `config-references.js` 的移植——删除出站代理 / 代理集合 / 代理组 / 规则集合前，
 *    找出草稿里还引用它的位置（规则与子规则的出口策略和 RULE-SET、逻辑规则里的嵌套条件、代理组的 proxies / use、
 *    任何位置的 dialer-proxy / proxy、隧道单行写法的第 4 段、DNS 的 fake-ip-filter / nameserver-policy /
 *    route-address-set / `#` 片段…）。有引用或无法安全判断时拒绝删除，并给出位置清单。
 * 3. [RenameSync]：参考实现各编辑器「改名后同步引用」的移植（节点 → 代理组 proxies；代理组 → 其它组的 proxies +
 *    rules / sub-rules 的尾部策略；代理集合 → 代理组 use；规则集合 → `RULE-SET,名字` 规则），产出一串补丁操作，
 *    由 FormHost 一次性原子应用。
 */
internal object PlainYaml {

    private class Ctx(val doc: YamlDoc) {
        val anchors = HashMap<String, Any?>()
    }

    /** 整篇文档 → 顶层映射（根不是映射时给空映射）。 */
    fun toPlain(doc: YamlDoc): Map<String, Any?> = toPlain(doc, doc.root)

    /** 任意节点 → Map / List / 标量树（锚点 / `<<:` 合并同样展开；节点不是映射时给空映射）。 */
    fun toPlain(doc: YamlDoc, node: YamlNode?): Map<String, Any?> {
        val ctx = Ctx(doc)
        val v = conv(ctx, node)
        @Suppress("UNCHECKED_CAST")
        return (v as? Map<String, Any?>) ?: emptyMap()
    }

    private fun conv(ctx: Ctx, node: YamlNode?): Any? {
        if (node == null) return null
        val result: Any? = when (node.kind) {
            YamlNode.Kind.MAP -> {
                val m = LinkedHashMap<String, Any?>()
                for (e in node.entries) {
                    if (e.isMerge) {
                        // `<<: *base` / `<<: [*a, *b]`：已有的键不被覆盖（js-yaml mergeMappings），后写的显式键再覆盖
                        val merged = conv(ctx, e.node)
                        val sources: List<Any?> = if (merged is List<*>) merged else listOf(merged)
                        for (src in sources) {
                            if (src is Map<*, *>) {
                                for ((k, v) in src) {
                                    if (k is String && k !in m) m[k] = v
                                }
                            }
                        }
                    } else {
                        m[e.key] = conv(ctx, e.node)
                    }
                }
                m
            }
            YamlNode.Kind.SEQ -> node.items.map { conv(ctx, it) }
            else -> scalar(ctx, node)
        }
        node.anchor?.let { ctx.anchors[it] = result }
        return result
    }

    private fun scalar(ctx: Ctx, node: YamlNode): Any? {
        node.alias?.let { name -> return if (ctx.anchors.containsKey(name)) ctx.anchors[name] else "*$name" }
        if (node.multi) return blockScalar(ctx.doc, node)
        val text = FormValues.scalarText(ctx.doc, node) ?: return null
        val line = ctx.doc.lines[node.start]
        val raw = line.substring(node.valueStart.coerceIn(0, line.length), node.valueEnd.coerceIn(0, line.length)).trim()
        if (raw.startsWith("'") || raw.startsWith("\"")) return text
        return when (text) {
            "", "~", "null", "Null", "NULL" -> null
            "true", "True", "TRUE" -> true
            "false", "False", "FALSE" -> false
            else -> FormValues.typedScalar(text)
        }
    }
}

/**
 * `|` / `>` 块标量按 YAML 的 chomping 规则还原成字符串（`|` 留一个换行、`|-` 不留、`|+` 全留；`>` 折行），
 * 和 js-yaml / PyYAML 读出来的一致。FormValues.scalarText 是给表单显示用的（去掉尾部空白），不能拿来比对。
 */
private fun blockScalar(doc: YamlDoc, node: YamlNode): String {
    val head = doc.lines[node.start]
    val header = head.substring(node.valueStart.coerceIn(0, head.length), node.valueEnd.coerceIn(0, head.length)).trim()
    val folded = header.startsWith(">")
    val chomp = when {
        header.contains('-') -> "strip"
        header.contains('+') -> "keep"
        else -> "clip"
    }
    val body = doc.lines.subList((node.start + 1).coerceAtMost(doc.lines.size), node.end.coerceAtMost(doc.lines.size))
    val explicit = header.drop(1).firstOrNull { it.isDigit() }?.digitToInt()
    val indent = explicit?.let { indentOfHead(head) + it } ?: (body.firstOrNull { it.isNotBlank() }?.let { it.length - it.trimStart().length } ?: 0)
    val lines = body.map { if (it.length >= indent) it.substring(indent) else "" }
    // 尾部空行按 chomping 处理；正文里的空行原样保留
    var endIdx = lines.size
    while (endIdx > 0 && lines[endIdx - 1].isBlank()) endIdx--
    val content = lines.subList(0, endIdx)
    val text = if (!folded) content.joinToString("\n") else buildString {
        var prevMore = false
        var first = true
        for (ln in content) {
            val more = ln.isNotEmpty() && ln[0].isWhitespace()
            when {
                first -> append(ln)
                ln.isEmpty() -> append('\n')
                more || prevMore -> { if (!endsWith("\n")) append('\n'); append(ln) }
                else -> { if (!endsWith("\n")) append(' '); append(ln) }
            }
            prevMore = more
            first = false
        }
    }
    return when (chomp) {
        "strip" -> text
        "keep" -> text + "\n".repeat((lines.size - endIdx) + 1)
        else -> if (content.isEmpty()) "" else text + "\n"
    }
}

private fun indentOfHead(line: String): Int = line.length - line.trimStart().length

internal object ConfigRefs {

    val DELETE_LABELS: Map<String, String> = linkedMapOf(
        "proxies" to "出站代理", "proxy-providers" to "代理集合", "proxy-groups" to "代理组", "rule-providers" to "规则集合",
    )
    private val arrayKinds = setOf("proxies", "proxy-groups")
    private val FLAG_RE = Regex("^(no-resolve|src)$", RegexOption.IGNORE_CASE)
    private val LOGIC_HEAD_RE = Regex("^(RULE-SET|AND|OR|NOT),", RegexOption.IGNORE_CASE)

    class Result(val references: List<String>, val issues: List<String>)

    /** 删除前的判定：error（条目找不到 / 同名多条…）、references（仍被引用的位置）、issues（无法安全判断的位置）。 */
    class Deletion(val error: String?, val references: List<String>, val issues: List<String>, val index: Int) {
        val blocked: Boolean get() = error != null || references.isNotEmpty() || issues.isNotEmpty()
        val reason: String
            get() = error ?: if (references.isNotEmpty()) "仍有 ${references.size} 处引用，请先解除引用。" else "无法安全确认引用，请先修正配置。"
        val locations: List<String> get() = references + issues
    }

    private fun at(path: String, key: String): String = path + "[" + jsonString(key) + "]"

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    /** 顶层按逗号拆（括号 / 引号里的逗号不算）。 */
    private fun tokens(value: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var depth = 0
        var quote = ' '
        var escape = false
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (escape) { escape = false; i++; continue }
            if (ch == '\\') { escape = true; i++; continue }
            if (quote != ' ') { if (ch == quote) quote = ' '; i++; continue }
            if (ch == '"' || ch == '\'') { quote = ch; i++; continue }
            if (ch == '(') depth++
            if (ch == ')') { depth--; if (depth < 0) throw IllegalArgumentException("规则括号不匹配") }
            if (ch == ',' && depth == 0) { out.add(value.substring(start, i).trim()); start = i + 1 }
            i++
        }
        if (depth != 0 || quote != ' ') throw IllegalArgumentException("规则括号或引号不匹配")
        out.add(value.substring(start).trim())
        return out
    }

    /** 逻辑规则的条件组：`((A),(B))`、`(A),(B)` 或紧挨的 `(A)(B)`，只拆最外层配对的括号。 */
    private fun conditionGroups(value: String): List<String> {
        val groups = ArrayList<String>()
        var start = -1
        var depth = 0
        var quote = ' '
        var escape = false
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (start < 0) {
                if (ch.isWhitespace() || ch == ',') { i++; continue }
                if (ch != '(') throw IllegalArgumentException("逻辑条件缺少括号")
                start = i; depth = 1; i++; continue
            }
            if (escape) { escape = false; i++; continue }
            if (ch == '\\') { escape = true; i++; continue }
            if (quote != ' ') { if (ch == quote) quote = ' '; i++; continue }
            if (ch == '"' || ch == '\'') { quote = ch; i++; continue }
            if (ch == '(') depth++
            if (ch == ')') { depth--; if (depth == 0) { groups.add(value.substring(start + 1, i).trim()); start = -1 } }
            i++
        }
        if (start >= 0 || groups.isEmpty()) throw IllegalArgumentException("逻辑条件括号不完整")
        return groups
    }

    private fun atom(value: String): String =
        if ((value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) ||
            (value.startsWith("'") && value.endsWith("'") && value.length >= 2)
        ) value.substring(1, value.length - 1) else value

    private fun nameOf(item: Any?): String? = (item as? Map<*, *>)?.get("name") as? String

    fun findReferences(cfg: Map<String, Any?>, kind: String, name: String): Result = Finder(cfg, kind, name).run()

    /** findConfigReferences 的逐行移植；rule / conditions 互相递归，所以放成类成员。 */
    private class Finder(val cfg: Map<String, Any?>, val kind: String, val name: String) {
        val refs = LinkedHashSet<String>()
        val issues = LinkedHashSet<String>()
        val policy = kind == "proxies" || kind == "proxy-groups"
        val ruleSet = kind == "rule-providers"
        val stack = HashSet<IdentityKey>()

        fun add(value: Any?, path: String) { if (value is String && value == name) refs.add(path) }

        fun rule(raw: Any?, path: String, condition: Boolean = false, depth: Int = 0) {
            if (raw !is String) { issues.add("$path：规则不是文本"); return }
            if (depth > 64) { issues.add("$path：规则嵌套过深"); return }
            val fields = try { tokens(raw) } catch (_: IllegalArgumentException) {
                issues.add("$path：规则格式复杂或不完整，无法安全检查"); return
            }
            val type = fields.getOrNull(0)?.uppercase()
            if (ruleSet && type == "RULE-SET" && !fields.getOrNull(1).isNullOrEmpty()) add(atom(fields[1]), "$path → RULE-SET")
            if (policy && !condition && type != "SUB-RULE") {
                val tail = ArrayList(fields)
                while (tail.size > 1 && FLAG_RE.matches(tail.last())) tail.removeAt(tail.lastIndex)
                if ((type == "MATCH" && tail.size >= 2) || (type != "MATCH" && tail.size >= 3)) add(atom(tail.last()), "$path → 出口策略")
            }
            if (type == "AND" || type == "OR" || type == "NOT" || type == "SUB-RULE") {
                val parts = ArrayList(fields.drop(1))
                if (!condition) {
                    while (parts.isNotEmpty() && FLAG_RE.matches(parts.last())) parts.removeAt(parts.lastIndex)
                    if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)   // 外层策略 / SUB-RULE 目标：不是匹配条件
                }
                if (parts.isEmpty()) { issues.add("$path：逻辑条件缺失"); return }
                conditions(parts.joinToString(","), path, depth + 1)
            }
        }

        fun conditions(raw: String, path: String, depth: Int) {
            if (depth > 64) { issues.add("$path：规则嵌套过深"); return }
            val groups = try { conditionGroups(raw) } catch (_: IllegalArgumentException) {
                issues.add("$path：逻辑条件无法安全检查"); return
            }
            groups.forEachIndexed { i, inner ->
                val location = "$path → 逻辑条件[$i]"
                if (inner.startsWith("(")) conditions(inner, location, depth + 1) else rule(inner, location, true, depth + 1)
            }
        }

        // 递归栈保护而不是全局 visited：别名能让同一对象出现在多个独立位置，每处都要查
        fun walk(value: Any?, path: String, depth: Int = 0) {
            if (value == null || (value !is Map<*, *> && value !is List<*>)) return
            val id = IdentityKey(value)
            if (id in stack || depth > 100) { issues.add("$path：循环引用或嵌套过深"); return }
            stack.add(id)
            val entries: List<Pair<String, Any?>> =
                if (value is Map<*, *>) value.entries.map { (it.key?.toString() ?: "null") to it.value }
                else (value as List<*>).mapIndexed { i, v -> i.toString() to v }
            for ((key, child) in entries) {
                val p = at(path, key)
                if (policy && (key == "dialer-proxy" || key == "proxy")) add(child, p)
                if (ruleSet && (key == "route-address-set" || key == "route-exclude-address-set" || key == "bypass-rule-set")) {
                    if (child is List<*>) child.forEachIndexed { i, v -> add(v, "$p[$i]") } else add(child, p)
                }
                if (ruleSet && (key == "nameserver-policy" || key == "proxy-server-nameserver-policy") && child is Map<*, *>) {
                    for (domain in child.keys) {
                        val d = domain?.toString() ?: continue
                        if (d.startsWith("rule-set:")) d.substring(9).split(',').map { it.trim() }.forEach { v -> add(v, at(p, d)) }
                    }
                }
                if (policy && path.startsWith("config[\"dns\"]") && child is String && child.contains('#')) {
                    for (part in child.substring(child.indexOf('#') + 1).split('&')) {
                        val decoded = urlDecode(part)
                        if (decoded != "RULES") add(decoded, "$p → DNS 出口")
                    }
                }
                walk(child, p, depth + 1)
            }
            stack.remove(id)
        }

        fun run(): Result {
            if (policy || ruleSet) {
                val rules = cfg["rules"]
                if (rules != null && rules !is List<*>) issues.add("rules：规则列表类型不正确")
                if (rules is List<*>) rules.forEachIndexed { i, r -> rule(r, "rules[$i]") }
                val sub = cfg["sub-rules"]
                if (sub is Map<*, *>) {
                    for ((group, list) in sub) {
                        if (group is String && list is List<*>) list.forEachIndexed { i, r -> rule(r, at("sub-rules", group) + "[$i]") }
                    }
                }
            }
            val dns = cfg["dns"]
            if (ruleSet && dns is Map<*, *>) {
                val filters = dns["fake-ip-filter"]
                if (filters != null && filters !is List<*>) issues.add("dns.fake-ip-filter：过滤列表类型不正确")
                if (filters is List<*>) filters.forEachIndexed { i, entry ->
                    val path = "dns.fake-ip-filter[$i]"
                    if (entry !is String) { issues.add("$path：过滤条目不是文本"); return@forEachIndexed }
                    val text = entry.trim()
                    if (text.startsWith("rule-set:", ignoreCase = true)) {
                        text.substring(text.indexOf(':') + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { provider -> add(provider, "$path → RULE-SET") }
                    } else if (dns["fake-ip-filter-mode"] == "rule" || LOGIC_HEAD_RE.containsMatchIn(text)) {
                        rule(text, path)
                    }
                }
            }
            // 只排除「正要删的这一条」自身：它对外的引用随它一起消失；其它条目（哪怕共用别名）照查
            for ((section, value) in cfg) {
                if (section == "rules" || section == "sub-rules") continue
                if (section == "proxy-groups" && value is List<*>) {
                    value.forEachIndexed { i, g ->
                        if (kind == section && nameOf(g) == name) return@forEachIndexed
                        val gm = g as? Map<*, *>
                        val p = "proxy-groups[$i] (${nameOf(g) ?: "未命名"})"
                        val proxies = gm?.get("proxies")
                        val use = gm?.get("use")
                        if (policy && proxies != null && proxies !is List<*>) issues.add("$p.proxies：成员列表类型不正确")
                        if (kind == "proxy-providers" && use != null && use !is List<*>) issues.add("$p.use：集合引用列表类型不正确")
                        if (policy && proxies is List<*>) proxies.forEachIndexed { n, v -> add(v, "$p.proxies[$n]") }
                        if (kind == "proxy-providers" && use is List<*>) use.forEachIndexed { n, v -> add(v, "$p.use[$n]") }
                        walk(g, at("config", section) + "[$i]")
                    }
                } else if (section == kind && kind in arrayKinds && value is List<*>) {
                    value.forEachIndexed { i, item -> if (nameOf(item) != name) walk(item, at("config", section) + "[$i]") }
                } else if (section == kind && kind !in arrayKinds && value is Map<*, *>) {
                    for ((key, item) in value) if (key != name) walk(item, at(at("config", section), key.toString()))
                } else {
                    walk(value, at("config", section))
                }
                if (policy && section == "tunnels" && value is List<*>) value.forEachIndexed { i, v ->
                    if (v is String) {
                        try {
                            val parts = tokens(v)
                            if (parts.size >= 4) add(atom(parts[3]), "tunnels[$i] → 出口策略")
                        } catch (_: IllegalArgumentException) { issues.add("tunnels[$i]：格式不完整") }
                    }
                }
            }
            return Result(refs.toList(), issues.toList())
        }
    }

    /** 参考实现 deletionState：条目必须唯一存在，再找引用。 */
    fun deletionState(doc: YamlDoc, kind: String, name: String): Deletion {
        if (kind !in DELETE_LABELS || name.isEmpty()) return Deletion("条目类型或名称无效，请刷新页面检查配置", emptyList(), emptyList(), -1)
        val cfg = PlainYaml.toPlain(doc)
        val collection = cfg[kind]
        var index = -1
        if (kind in arrayKinds) {
            if (collection !is List<*>) return Deletion("条目已不存在或配置类型已改变，请刷新页面", emptyList(), emptyList(), -1)
            val matches = collection.indices.filter { nameOf(collection[it]) == name }
            if (matches.size != 1) {
                return Deletion(if (matches.isNotEmpty()) "存在同名条目，无法安全确定删除对象，请先修正名称" else "条目已不存在或已重命名，请刷新页面",
                    emptyList(), emptyList(), -1)
            }
            index = matches[0]
        } else {
            if (collection !is Map<*, *> || !collection.containsKey(name)) return Deletion("条目已不存在或配置类型已改变，请刷新页面", emptyList(), emptyList(), -1)
        }
        val r = findReferences(cfg, kind, name)
        return Deletion(null, r.references, r.issues, index)
    }

    private fun urlDecode(s: String): String {
        if (!s.contains('%')) return s
        return try {
            val bytes = ArrayList<Byte>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val b = hex.toIntOrNull(16) ?: throw IllegalArgumentException()
                    bytes.add(b.toByte()); i += 3
                } else {
                    for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
                    i++
                }
            }
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) { s }
    }

    /** 按对象身份比较的包装（Map / List 的 equals 是按内容的，环检测需要身份）。 */
    private class IdentityKey(val v: Any) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.v === v
        override fun hashCode(): Int = System.identityHashCode(v)
    }
}

/** 一步补丁操作（FormHost.batch 顺序应用，任一步被引擎拒绝则整批不写）。 */
internal sealed class BatchOp {
    class Set(val path: List<Any>, val value: Any?) : BatchOp()
    class Remove(val path: List<Any>) : BatchOp()
    class SetItem(val seqPath: List<Any>, val index: Int, val value: Any?) : BatchOp()
    class Rename(val path: List<Any>, val newKey: String) : BatchOp()
    class InsertItem(val seqPath: List<Any>, val index: Int, val value: Any?) : BatchOp()
}

internal object RenameSync {

    /**
     * ops 是要一次提交的操作；synced 是顺手改掉的引用数。
     * blocked 非空表示有引用藏在 `*别名` / 锚点里（引擎只改字面文本，改不到别名展开后的内容）：此时不能只改一半，
     * UI 应整个拒绝并提示去编辑器里处理。
     */
    class Plan(val ops: List<BatchOp>, val synced: Int, val blocked: String? = null)

    /** 用展开后的结构（别名 / 合并键解开，和参考实现眼里的配置一致）数一遍应改的引用，和字面扫描数不一致就是有引用藏在别名里。 */
    private fun check(doc: YamlDoc, ops: List<BatchOp>, synced: Int, expected: Int): Plan {
        if (expected == synced) return Plan(ops, synced)
        return Plan(ops, synced, "有 ${expected - synced} 处引用写在锚点 / 别名（`*名字`）展开的内容里，表单改不到；请先在编辑器里处理这些别名，再改名")
    }

    private fun plainGroups(plain: Map<String, Any?>): List<Map<*, *>> =
        (plain["proxy-groups"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

    private fun plainListCount(groups: List<Map<*, *>>, key: String, old: String, skip: Int = -1): Int {
        var n = 0
        groups.forEachIndexed { gi, g -> if (gi != skip) (g[key] as? List<*>)?.forEach { if (it == old) n++ } }
        return n
    }

    private fun plainRuleCount(plain: Map<String, Any?>, includeSubRules: Boolean, fix: (String) -> String?): Int {
        var n = 0
        (plain["rules"] as? List<*>)?.forEach { if (it != null && it !is Map<*, *> && it !is List<*> && fix(it.toString()) != null) n++ }
        if (includeSubRules) (plain["sub-rules"] as? Map<*, *>)?.values?.forEach { list ->
            (list as? List<*>)?.forEach { if (it != null && it !is Map<*, *> && it !is List<*> && fix(it.toString()) != null) n++ }
        }
        return n
    }

    private fun groups(doc: YamlDoc): List<YamlNode> {
        val seq = doc.get(listOf("proxy-groups")) ?: return emptyList()
        return if (seq.kind == YamlNode.Kind.SEQ) seq.items else emptyList()
    }

    /** 某个代理组的 proxies / use 列表里等于 old 的项 → 改成 new。 */
    private fun listRefs(doc: YamlDoc, gi: Int, key: String, old: String, new: String, ops: MutableList<BatchOp>): Int {
        val path = listOf<Any>("proxy-groups", gi, key)
        val list = doc.get(path) ?: return 0
        if (list.kind != YamlNode.Kind.SEQ) return 0
        var n = 0
        list.items.forEachIndexed { j, item ->
            if (FormValues.scalarText(doc, item) == old) { ops.add(BatchOp.Set(path + j, new)); n++ }
        }
        return n
    }

    /** 参考实现 fixRule：末段（跳过 no-resolve）等于旧名则换成新名，其余段原样。 */
    fun fixRuleTail(rule: String, old: String, new: String): String? {
        val parts = rule.split(',').toMutableList()
        if (parts.size < 2) return null
        var pi = parts.size - 1
        if (parts[pi].trim() == "no-resolve") pi -= 1
        if (pi >= 1 && parts[pi].trim() == old) { parts[pi] = new; return parts.joinToString(",") }
        return null
    }

    /** 参考实现规则集合改名：`RULE-SET,旧名,…` → `RULE-SET,新名,…`。 */
    fun fixRuleSet(rule: String, old: String, new: String): String? {
        val parts = rule.split(',').toMutableList()
        if (parts.size >= 3 && parts[0].trim() == "RULE-SET" && parts[1].trim() == old) { parts[1] = new; return parts.joinToString(",") }
        return null
    }

    private fun ruleLists(doc: YamlDoc, includeSubRules: Boolean): List<List<Any>> {
        val out = ArrayList<List<Any>>()
        out.add(listOf("rules"))
        if (includeSubRules) {
            val sub = doc.get(listOf("sub-rules"))
            if (sub != null && sub.kind == YamlNode.Kind.MAP) {
                for (e in sub.entries) {
                    if (!e.isMerge) out.add(listOf("sub-rules", e.key))
                }
            }
        }
        return out
    }

    private fun fixRules(doc: YamlDoc, lists: List<List<Any>>, fix: (String) -> String?, ops: MutableList<BatchOp>): Int {
        var n = 0
        for (seqPath in lists) {
            val seq = doc.get(seqPath) ?: continue
            if (seq.kind != YamlNode.Kind.SEQ) continue
            seq.items.forEachIndexed { i, item ->
                if (item.kind == YamlNode.Kind.MAP || item.kind == YamlNode.Kind.SEQ) return@forEachIndexed
                val raw = FormValues.scalarText(doc, item) ?: return@forEachIndexed
                val fixed = fix(raw) ?: return@forEachIndexed
                ops.add(BatchOp.SetItem(seqPath, i, fixed)); n++
            }
        }
        return n
    }

    /** 节点改名（editProxySheet）：本节点 name + 全部代理组 proxies 里的引用。 */
    fun proxy(doc: YamlDoc, seqPath: List<Any>, index: Int, old: String, new: String): Plan {
        val ops = ArrayList<BatchOp>()
        ops.add(BatchOp.Set(seqPath + index + "name", new))
        var n = 0
        if (seqPath != listOf<Any>("proxies")) return Plan(ops, 0)
        for (gi in groups(doc).indices) n += listRefs(doc, gi, "proxies", old, new, ops)
        return check(doc, ops, n, plainListCount(plainGroups(PlainYaml.toPlain(doc)), "proxies", old))
    }

    /** 代理组改名（editGroupSheet）：本组 name + 其它组的 proxies + rules / sub-rules 的尾部策略。 */
    fun group(doc: YamlDoc, index: Int, old: String, new: String): Plan {
        val ops = ArrayList<BatchOp>()
        ops.add(BatchOp.Set(listOf("proxy-groups", index, "name"), new))
        var n = 0
        for (gi in groups(doc).indices) if (gi != index) n += listRefs(doc, gi, "proxies", old, new, ops)
        n += fixRules(doc, ruleLists(doc, includeSubRules = true), { fixRuleTail(it, old, new) }, ops)
        val plain = PlainYaml.toPlain(doc)
        val expected = plainListCount(plainGroups(plain), "proxies", old, skip = index) +
            plainRuleCount(plain, includeSubRules = true) { fixRuleTail(it, old, new) }
        return check(doc, ops, n, expected)
    }

    /** 代理集合改名（editSubSheet）：映射键 + 代理组 use。 */
    fun provider(doc: YamlDoc, old: String, new: String): Plan {
        val ops = ArrayList<BatchOp>()
        ops.add(BatchOp.Rename(listOf("proxy-providers", old), new))
        var n = 0
        for (gi in groups(doc).indices) n += listRefs(doc, gi, "use", old, new, ops)
        return check(doc, ops, n, plainListCount(plainGroups(PlainYaml.toPlain(doc)), "use", old))
    }

    /** 规则集合改名（editEpSheet）：映射键 + rules 里的 `RULE-SET,名字`（参考实现只改顶层 rules）。 */
    fun ruleProvider(doc: YamlDoc, old: String, new: String): Plan {
        val ops = ArrayList<BatchOp>()
        ops.add(BatchOp.Rename(listOf("rule-providers", old), new))
        val n = fixRules(doc, ruleLists(doc, includeSubRules = false), { fixRuleSet(it, old, new) }, ops)
        return check(doc, ops, n, plainRuleCount(PlainYaml.toPlain(doc), includeSubRules = false) { fixRuleSet(it, old, new) })
    }
}
