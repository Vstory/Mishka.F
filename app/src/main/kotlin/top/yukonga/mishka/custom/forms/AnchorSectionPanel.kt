package top.yukonga.mishka.custom.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.platform.showToast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 详情页的「YAML 锚点」区（对齐 mihomo_box `anchorSection`）：一级 = 条目键行 `<<: *name` 合并继承
 * （换绑 / 清除 / 新挂）；二级 = 条目内子字段的 `<<:` 合并与 `键: *name` 整体引用。
 * `&定义` 动作已下线：存量字段级 `&名` 只能摘除。候选锚点只列**定义在本条目行之前**的（YAML 无前向别名），
 * `<<:` 只列映射锚点——两条硬规则与锚点面板一致。
 */

/** 各合集的已知字段序（参考实现 knownFieldsByTop），二级选择器按它排。 */
private val ANCHOR_FIELD_CANDIDATES: Map<String, List<String>> = mapOf(
    "proxy-providers" to listOf(
        "type", "url", "interval", "proxy", "path", "header", "format",
        "health-check", "override", "filter", "exclude-filter", "exclude-type",
    ),
    "proxy-groups" to listOf(
        "name", "type", "proxies", "use", "url", "interval", "timeout", "tolerance", "lazy",
        "expected-status", "max-failed-times", "hidden", "icon", "filter", "exclude-filter",
        "exclude-type", "strategy", "disable-udp", "interface-name", "routing-mark",
    ),
    "rule-providers" to listOf("type", "behavior", "url", "path", "interval", "proxy", "header", "format"),
)

/** 字段级锚点状态的「值」部分（定义 &x / 继承 <<: *y / 引用 *z），不含键名——键、值分行显示用。 */
private fun fieldAnchorValueText(f: FieldAnchorState): String = buildString {
    val parts = ArrayList<String>()
    f.defAnchor?.let { parts.add("定义 &$it") }
    if (f.merges.isNotEmpty()) parts.add(if (f.multi) "继承 <<: 多来源（锁定）" else "继承 <<: *${f.merges.first()}")
    f.aliasRef?.let { parts.add("引用 *$it") }
    append(parts.joinToString(" · "))
}

@Composable
internal fun AnchorSectionCard(host: FormHost, topKey: String, base: YPath) {
    val doc = host.doc
    val l1 = remember(doc, base) { AnchorInheritance.entryMerges(doc, base) }
    val states = remember(doc, base) { AnchorInheritance.fieldStates(doc, base) }
    val defs = remember(doc) { AnchorInheritance.anchorDefs(doc) }
    val refCounts = remember(doc) { AnchorInheritance.anchorRefCounts(doc) }
    val entry = doc.get(base)
    // 块映射节点的 start 是第一个子行，键行要用 headLine0 推，否则「定义在本条目之前」会错偏一行
    val headLine1 = entry?.let { AnchorInheritance.headLine0(it) + 1 } ?: 0
    var pickL1 by remember { mutableStateOf(false) }
    var pickField by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("YAML 锚点")
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
            val l1Def = defs.firstOrNull { it.name == l1.merges.firstOrNull() }
            BasicComponent(
                title = "继承锚点 <<:",
                summary = when {
                    l1.multi -> "合并来源 ${l1.merges.size} 个（${l1.merges.joinToString("、") { "*$it" }}），为不破坏原结构已锁定；如需调整请在编辑器里改"
                    l1.merges.isNotEmpty() -> "继承 *${l1.merges.first()}" +
                        (l1Def?.let { "（定义在第 ${it.line1} 行）" } ?: "") +
                        " · 可换绑到其它锚点，或选「(不继承)」删除这一行"
                    else -> "未继承 · 选一个锚点，把它的字段并进来当默认（本地同名字段仍是覆写）"
                },
                endActions = { TextButton(text = "选择", onClick = { pickL1 = true }) },
                onClick = { pickL1 = true },
            )
        }
        // 字段级锚点：每个在用字段单独一行——标题只放键名，摘要放锚点状态（键与值分行，不再挤成一条 chips 串）
        val activeFields = states.filter { it.defAnchor != null || it.aliasRef != null || it.merges.isNotEmpty() }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "二级锚点（字段级）",
                    summary = if (activeFields.isEmpty()) {
                        "未设置字段级锚点 · 点右侧 ✎ 可给某个字段挂 <<: 继承或 * 引用"
                    } else {
                        "${activeFields.size} 个字段挂了字段级锚点"
                    },
                    endActions = { MiniIconButton(MiuixIcons.Edit, "编辑") { pickField = true } },
                    onClick = { pickField = true },
                )
                activeFields.forEach { f ->
                    BasicComponent(
                        title = f.key,
                        summary = fieldAnchorValueText(f) + " · 第 ${f.line1} 行",
                    )
                }
            }
        }
        // 锚点定义一览：名字 / 宿主键 / 定义行号 / 形态 / 全文引用次数，本条目正在用的标出来
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "锚点定义（${defs.size}）",
                    summary = if (defs.isEmpty()) {
                        "文档里还没有锚点：先在编辑器里给某个字段写 `&名字`，再回来挂继承 / 引用"
                    } else {
                        "标题是锚点名，摘要里「定义于」是承载它的 YAML 键 · 映射才能被 <<: 继承，标量 / 序列只能整体 * 引用"
                    },
                )
                if (defs.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        val entryAnchor = entry?.anchor
                        defs.forEach { d ->
                            val inheritedHere = d.name in l1.merges
                            val fieldUses = states.filter { d.name in it.merges || it.aliasRef == d.name }.map { it.key }
                            BasicComponent(
                                title = buildString {
                                    append("&").append(d.name)
                                    when {
                                        d.name == entryAnchor -> append("  ← 本条目定义")
                                        inheritedHere -> append("  ← 本条目继承中")
                                        fieldUses.isNotEmpty() -> append("  ← 本条目字段 " + fieldUses.joinToString("/") + " 在用")
                                    }
                                },
                                summary = buildString {
                                    append("定义于「").append(d.ownerKey ?: "序列项").append("」· 第 ").append(d.line1).append(" 行 · ")
                                    append(if (d.isMap) "映射（可 <<: 继承）" else "标量 / 序列（只能 * 引用）")
                                    append(" · 全文引用 ").append(refCounts[d.name] ?: 0).append(" 处")
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (pickL1) {
        val options = listOf(FormOption("", "(不继承)")) +
            defs.filter { it.line1 < headLine1 && it.isMap }.map { FormOption(it.name, it.name) }
        OptionPickerDialog(
            title = "继承锚点",
            summary = "只列定义在本条目之前、且是映射的锚点；选「(不继承)」删除 <<: 行",
            options = options,
            current = if (l1.merges.size == 1) l1.merges.first() else null,
            onDismiss = { pickL1 = false },
            onPick = { v ->
                pickL1 = false
                val want = v.ifEmpty { null }
                if (l1.merges.size == 1 && want == l1.merges.first()) return@OptionPickerDialog
                if (want == null && l1.merges.isEmpty()) return@OptionPickerDialog
                val newText = AnchorInheritance.setEntryMerge(doc, base, want)
                if (newText == null) showToast("继承未改动：这个位置不能这样写，请在编辑器里直接改", long = true)
                else host.applyRawText(newText, "继承锚点", entry?.let { it.start + 1 })
            },
        )
    }
    if (pickField) {
        FieldAnchorDialog(
            host = host, base = base, topKey = topKey, states = states,
            defs = defs, headLine1 = headLine1, entryLine0 = entry?.start ?: 0,
            onDismiss = { pickField = false },
        )
    }
}

/** 二级锚点编辑弹层：现状 chips（× 摘除）+ 字段 / 动作 / 锚点三选 + 应用。 */
@Composable
private fun FieldAnchorDialog(
    host: FormHost,
    base: YPath,
    topKey: String,
    states: List<FieldAnchorState>,
    defs: List<AnchorDefInfo>,
    headLine1: Int,
    entryLine0: Int,
    onDismiss: () -> Unit,
) {
    val doc = host.doc
    val known = ANCHOR_FIELD_CANDIDATES[topKey].orEmpty()
    // 本地字段 + 已知但源码没写的字段（可新挂）；已知序在前
    val fieldNames = (states.map { it.key } + known).distinct().sortedBy { k ->
        val i = known.indexOf(k); if (i >= 0) i else 999
    }
    var field by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("merge") }
    var anchor by remember { mutableStateOf<String?>(null) }
    var pickField by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    var pickAnchor by remember { mutableStateOf(false) }

    fun stateOf(key: String): FieldAnchorState? = states.firstOrNull { it.key == key }

    fun apply() {
        val key = field ?: run { showToast("请先选择字段", long = true); return }
        val name = anchor
        val out = when (mode) {
            "alias" -> {
                if (name == null) {
                    // 清除整体引用：用展开视图里的生效标量回填；非标量宁不动
                    val restore = FormValues.effectiveText(doc, base + key)
                    AnchorInheritance.setFieldAlias(doc, base, key, null, restore)
                } else AnchorInheritance.setFieldAlias(doc, base, key, name, null)
            }
            else -> AnchorInheritance.setFieldMerge(doc, base, key, name)
        }
        if (out == null) showToast("字段锚点未改动：该字段形态不支持这个操作，请在编辑器里直接改", long = true)
        else host.applyRawText(out, "字段锚点 $key", entryLine0 + 1)
    }

    WindowDialog(
        show = true,
        title = "二级锚点（字段级）",
        summary = "继承 <<: 要求字段是映射；整体引用 * 任意值都行。选中锚点后该字段即以锚点内容生效。" +
            "字段级 &定义 已下线：存量的用上方 × 摘除，新增只支持继承与引用。",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val active = states.filter { it.defAnchor != null || it.aliasRef != null || it.merges.isNotEmpty() }
            if (active.isEmpty()) Text(
                text = "条目内没有字段级锚点",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            active.forEach { f ->
                BasicComponent(
                    title = f.key,
                    summary = fieldAnchorValueText(f) + " · 第 ${f.line1} 行",
                    endActions = {
                        if (!f.multi) MiniIconButton(MiuixIcons.Delete, "摘除字段锚点") {
                            val out = when {
                                f.aliasRef != null -> AnchorInheritance.setFieldAlias(
                                    doc, base, f.key, null, FormValues.effectiveText(doc, base + f.key),
                                )
                                f.merges.isNotEmpty() -> AnchorInheritance.setFieldMerge(doc, base, f.key, null)
                                f.defAnchor != null -> AnchorInheritance.removeFieldDef(doc, base, f.key)
                                else -> null
                            }
                            if (out == null) showToast("摘除未改动：请在编辑器里直接改", long = true)
                            else { onDismiss(); host.applyRawText(out, "字段锚点 ${f.key}", f.line1) }
                        }
                    },
                )
            }
            BasicComponent(
                title = "字段",
                summary = field ?: "(选择字段)",
                endActions = { TextButton(text = "选择", onClick = { pickField = true }) },
                onClick = { pickField = true },
            )
            BasicComponent(
                title = "动作",
                summary = if (mode == "merge") "继承合并 <<:" else "整体字段引用 *",
                endActions = { TextButton(text = "切换", onClick = { pickMode = true }) },
                onClick = { pickMode = true },
            )
            BasicComponent(
                title = "锚点",
                summary = anchor?.let { "*$it" } ?: if (mode == "merge") "(不继承)" else "(恢复为本地值)",
                endActions = { TextButton(text = "选择", onClick = { pickAnchor = true }) },
                onClick = { pickAnchor = true },
            )
            TextButton(text = "应用", onClick = { apply() })
        }
    }

    if (pickField) {
        OptionPickerDialog(
            title = "选择字段",
            options = fieldNames.map { k ->
                val st = stateOf(k)
                val mark = buildString {
                    st?.defAnchor?.let { append(" &$it") }
                    if (st?.merges?.isNotEmpty() == true) append(" «${st.merges.joinToString("+")}»")
                    st?.aliasRef?.let { append(" *$it") }
                }
                FormOption(k, k + mark)
            },
            current = field,
            onDismiss = { pickField = false },
            onPick = { field = it; pickField = false; anchor = null },
        )
    }
    if (pickMode) {
        OptionPickerDialog(
            title = "选择动作",
            options = listOf(FormOption("merge", "继承合并 <<:"), FormOption("alias", "整体字段引用 *")),
            current = mode,
            onDismiss = { pickMode = false },
            onPick = { mode = it; pickMode = false; anchor = null },
        )
    }
    if (pickAnchor) {
        // 字段级同样遵守「定义在引用行之前」：用字段行号（已有本地行）或条目行号（新挂）
        val beforeLine = stateOf(field.orEmpty())?.line1 ?: headLine1
        val options = listOf(FormOption("", if (mode == "merge") "(不继承)" else "(恢复为本地值)")) +
            defs.filter { it.line1 < beforeLine && (mode == "alias" || it.isMap) }.map { FormOption(it.name, it.name) }
        OptionPickerDialog(
            title = "选择锚点",
            options = options,
            current = anchor,
            onDismiss = { pickAnchor = false },
            onPick = { anchor = it.ifEmpty { null }; pickAnchor = false },
        )
    }
}
