package top.yukonga.mishka.custom.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import top.yukonga.mishka.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/*
 * 配置表单用到的弹层（和 ConfigFormPanel.kt / FlowFormPages.kt 配套）。
 * 控件语义对齐 mihomo_box WebUI 的 fields.js：
 *  - 下拉只从清单里挑（有候选时不提供手填）；允许留空的字段多一项「默认（不覆写）」= 删键；
 *  - 三态布尔 = 默认（不覆写）/ 开 / 关；
 *  - 带候选清单的列表（datalist）只能挑选、不能手填（手滑写错的名字内核会静默忽略）。
 */

/** 弹层底部的「取消 / 确定」行，统一为覆写设置中的等宽双按钮与强调色确定按钮。 */
@Composable
internal fun DialogButtons(onDismiss: () -> Unit, confirmLabel: String? = "确定", onConfirm: (() -> Unit)? = null) {
    val cancelLabel = stringResource(R.string.common_cancel)
    if (confirmLabel != null && onConfirm != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(text = cancelLabel, modifier = Modifier.weight(1f), onClick = onDismiss)
            TextButton(
                text = confirmLabel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onConfirm,
            )
        }
    } else {
        TextButton(
            text = cancelLabel,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            onClick = onDismiss,
        )
    }
}

@Composable
internal fun DialogNote(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * 下拉选择。静态选项来自字段表；`candidates` 是运行时算出来的（节点名 / 代理组名 / 集合名）。
 * 两者都为空时才给自定义输入（否则和参考实现一样只能挑选）。
 */
@Composable
internal fun SelectDialog(
    field: FormField,
    current: String?,
    candidates: List<FormOption>,
    hideUnavailableCurrent: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    val known = field.options.map { it.value }.toSet() + candidates.map { it.value }
    val allowEmpty = field.allowEmpty || field.optional || field.emptyLabel != null
    WindowDialog(
        show = true,
        title = field.label,
        summary = "`${field.path}`" + (field.desc?.let { " · $it" } ?: ""),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (allowEmpty) {
                    BasicComponent(
                        title = field.emptyLabel ?: "默认（不覆写）",
                        summary = "删掉这个键，回到内核默认 / 锚点继承值",
                        endActions = { if (current == null) Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                        onClick = { onPick(null) },
                    )
                }
                if (!hideUnavailableCurrent && current != null && current !in known) {
                    BasicComponent(
                        title = current,
                        summary = "当前值（不在清单里，保留原样）",
                        endActions = { Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                        onClick = { onPick(current) },
                    )
                }
                for (option in field.options) {
                    BasicComponent(
                        title = option.label,
                        summary = option.value.takeIf { it != option.label },
                        endActions = { if (option.value == current) Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                        onClick = { onPick(option.value) },
                    )
                }
                for (option in candidates) {
                    BasicComponent(
                        title = option.value,
                        summary = option.label.takeIf { it != option.value },
                        endActions = { if (option.value == current) Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                        onClick = { onPick(option.value) },
                    )
                }
            }
            if (field.options.isEmpty() && candidates.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = custom,
                        onValueChange = { custom = it },
                        modifier = Modifier.weight(1f),
                        label = field.placeholder ?: "文档里还没有可选项，手填…",
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "用这个", onClick = { custom.trim().takeIf { it.isNotEmpty() }?.let(onPick) })
                }
            }
            DialogButtons(onDismiss = onDismiss, confirmLabel = null)
        }
    }
}

/** 三态布尔（参考实现 fields.js 的 def / tri / boolAs=pick）：默认（不覆写）= 删键；开 / 关 = 显式写 true / false。 */
@Composable
internal fun TriBoolDialog(
    title: String,
    desc: String?,
    current: Boolean?,
    onDismiss: () -> Unit,
    onPick: (Boolean?) -> Unit,
    emptySummary: String = "从配置里删掉这个键，由内核默认 / 锚点继承决定",
) {
    WindowDialog(
        show = true,
        title = title,
        summary = desc,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "默认（不覆写）",
                summary = emptySummary,
                endActions = { if (current == null) Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                onClick = { onPick(null) },
            )
            BasicComponent(
                title = "开",
                summary = "写入 true",
                endActions = { if (current == true) Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                onClick = { onPick(true) },
            )
            BasicComponent(
                title = "关",
                summary = "写入 false",
                endActions = { if (current == false) Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                onClick = { onPick(false) },
            )
            DialogButtons(onDismiss = onDismiss, confirmLabel = null)
        }
    }
}

@Composable
internal fun TextValueDialog(
    title: String,
    summary: String?,
    desc: String?,
    current: String,
    multiLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    label: String = if (multiLine) "值（可多行）" else "值",
    confirmLabel: String = "确定",
) {
    var draft by remember { mutableStateOf(current) }
    WindowDialog(
        show = true,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = label,
                singleLine = !multiLine,
            )
            if (multiLine) DialogNote("多行内容会按 | 块写入（YAML 里保留换行）")
            desc?.let { DialogNote(it) }
            DialogButtons(onDismiss = onDismiss, confirmLabel = confirmLabel) {
                onConfirm(if (multiLine) draft.trimEnd() else draft.trim())
            }
        }
    }
}

/**
 * 数字列表（[FormFieldType.NUMLIST]：端口 / UID 这类）的项归一化：一项里带逗号说明是多个值
 * 一次粘进来了（「8888, 9999」），按逗号拆开——不拆整串就是一项，写回 YAML 时因含「, 」
 * 被引号包成 `'8888, 9999'`，内核解析不到。半角逗号、全角逗号与顿号（字段提示里就有
 * 「如 0、10」这种写法）都算分隔符。
 */
private fun normalizeNumListItems(items: List<String>): List<String> =
    items.flatMap { it.split(',', '，', '、') }.map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 字符串列表编辑：按住把手拖动排序（对齐 mihomo_box WebUI 的列表项把手）+ 行尾 × 删除；
 * `candidates` 非空时列出可勾选的候选，勾一下加进列表、再勾一下移出；
 * `pickOnly`（参考实现的 datalist 列表）时不给手填框。
 */
@Composable
internal fun ListDialog(
    field: FormField,
    current: List<String>,
    candidates: List<FormOption>,
    pickOnly: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val items = remember { mutableStateListOf<String>().also { it.addAll(current) } }
    var draft by remember { mutableStateOf("") }
    var showCandidates by remember { mutableStateOf(candidates.isNotEmpty() && (current.isEmpty() || pickOnly)) }
    val scrollState = rememberScrollState()
    // 拖动排序：草稿列表就地换位（写回仍走对话框的「确定」）
    val dragSort = rememberDragSortState(items.size) { from, to ->
        val moved = items.removeAt(from)
        items.add(to, moved)
    }
    WindowDialog(
        show = true,
        title = field.label,
        summary = "`${field.path}` · ${items.size} 项" + (field.hint?.let { " · $it" } ?: "") +
            (field.join?.let { " · 写成用「$it」拼接的一个字符串" } ?: ""),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .then(dragSort.containerModifier { scrollState.dispatchRawDelta(it) })
                    .verticalScroll(scrollState, enabled = dragSort.dragging < 0),
            ) {
                if (items.isEmpty()) DialogNote("（空）")
                // 行号列显示的是**当前档位**（拖动换位后重新编号），行内容按原始下标取
                dragSort.order.forEachIndexed { pos, index ->
                    if (index !in items.indices) return@forEachIndexed
                    val item = items[index]
                    DragSortRow(state = dragSort, index = index) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DragHandle(dragSort, index)
                            Text(
                                text = (pos + 1).toString().padStart(2),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                text = item,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            MiniIconButton(MiuixIcons.Delete, "删除") { items.removeAt(index) }
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "候选（${candidates.size}）",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = if (showCandidates) "收起" else "展开",
                        minWidth = 0.dp, minHeight = 0.dp,
                        onClick = { showCandidates = !showCandidates },
                    )
                }
                if (showCandidates) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (c in candidates) {
                            val picked = c.value in items
                            BasicComponent(
                                title = c.value,
                                summary = c.label.takeIf { it != c.value },
                                endActions = {
                                    Checkbox(
                                        state = if (picked) ToggleableState.On else ToggleableState.Off,
                                        onClick = { if (picked) items.remove(c.value) else items.add(c.value) },
                                    )
                                },
                                onClick = { if (picked) items.remove(c.value) else items.add(c.value) },
                            )
                        }
                    }
                }
            }
            if (!pickOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        label = field.placeholder ?: field.hint ?: "新增一项",
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "添加", onClick = {
                        val v = draft.trim()
                        if (v.isNotEmpty()) {
                            val sep = field.join
                            // 数字列表：一次粘贴多个端口（「8888, 9999」）按逗号拆成多项；join 型仍按拼接符拆
                            if (sep != null) v.split(sep).map { it.trim() }.filter { it.isNotEmpty() }.forEach { items.add(it) }
                            else if (field.type == FormFieldType.NUMLIST) normalizeNumListItems(listOf(v)).forEach { items.add(it) }
                            else items.add(v)
                            draft = ""
                        }
                    })
                }
            } else {
                DialogNote("只能从候选里挑：手填的名字写错了内核会静默忽略")
            }
            field.desc?.let { DialogNote(it) }
            DialogButtons(onDismiss = onDismiss) {
                // 确认时再归一化一次：存量列表里已经写坏的「'8888, 9999'」会在下一次确认时被拆回多项
                onConfirm(if (field.type == FormFieldType.NUMLIST) normalizeNumListItems(items.toList()) else items.toList())
            }
        }
    }
}

/** 键 → 值 的小映射（plugin-opts / HTTP 头）。`arrayValues` 时值按逗号拆成列表写回。 */
@Composable
internal fun MapDialog(
    field: FormField,
    current: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any?>) -> Unit,
) {
    val keys = remember { mutableStateListOf<String>().also { l -> l.addAll(current.map { it.first }) } }
    val values = remember { mutableStateListOf<String>().also { l -> l.addAll(current.map { it.second }) } }
    WindowDialog(
        show = true,
        title = field.label,
        summary = "`${field.path}` · ${keys.size} 项" + (field.desc?.let { " · $it" } ?: ""),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (index in keys.indices) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = keys[index],
                            onValueChange = { keys[index] = it },
                            modifier = Modifier.weight(0.42f),
                            label = field.keyPlaceholder ?: "键",
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextField(
                            value = values[index],
                            onValueChange = { values[index] = it },
                            modifier = Modifier.weight(0.58f),
                            label = if (field.arrayValues) "值（逗号分隔多个）" else "值",
                            singleLine = true,
                        )
                        Spacer(Modifier.width(4.dp))
                        MiniIconButton(MiuixIcons.Delete, "删除") {
                            keys.removeAt(index)
                            values.removeAt(index)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "加一项", onClick = { keys.add(""); values.add("") })
            }
            DialogNote(if (field.arrayValues) "值会按列表写入（`Key: [a, b]`）；整张表一起写回" else "值是数字 / true / false 时按原类型写入；整张表一起写回")
            DialogButtons(onDismiss = onDismiss) {
                val out = LinkedHashMap<String, Any?>()
                for (i in keys.indices) {
                    val k = keys[i].trim()
                    if (k.isEmpty()) continue
                    val v = values[i].trim()
                    out[k] = if (field.arrayValues) v.split(',').map { it.trim() }.filter { it.isNotEmpty() } else FormValues.typedScalar(v)
                }
                onConfirm(out)
            }
        }
    }
}

/** 固定几列的映射列表（override.proxy-name 的 pattern → target）。 */
@Composable
internal fun MapListDialog(
    field: FormField,
    columns: List<Pair<String, String>>,
    current: List<Map<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (List<Map<String, Any?>>) -> Unit,
) {
    val rows = remember {
        mutableStateListOf<List<String>>().also { l ->
            for (item in current) l.add(columns.map { (k, _) -> item[k].orEmpty() })
        }
    }
    WindowDialog(
        show = true,
        title = field.label,
        summary = "`${field.path}` · ${rows.size} 项" + (field.desc?.let { " · $it" } ?: ""),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (index in rows.indices) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for ((ci, col) in columns.withIndex()) {
                            if (ci > 0) Spacer(Modifier.width(8.dp))
                            TextField(
                                value = rows[index][ci],
                                onValueChange = { v -> rows[index] = rows[index].mapIndexed { j, s -> if (j == ci) v else s } },
                                modifier = Modifier.weight(1f),
                                label = col.second,
                                singleLine = true,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        MiniIconButton(MiuixIcons.Delete, "删除") { rows.removeAt(index) }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "加一项", onClick = { rows.add(columns.map { "" }) })
            }
            DialogButtons(onDismiss = onDismiss) {
                val out = ArrayList<Map<String, Any?>>()
                for (r in rows) {
                    if (r.all { it.isBlank() }) continue
                    val m = LinkedHashMap<String, Any?>()
                    for ((ci, col) in columns.withIndex()) m[col.first] = r[ci].trim()
                    out.add(m)
                }
                onConfirm(out)
            }
        }
    }
}

/** 文本输入 + 确认的小对话框（起名字用）。 */
@Composable
internal fun NameDialog(
    title: String,
    summary: String?,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    TextValueDialog(
        title = title,
        summary = summary,
        desc = null,
        current = initial,
        multiLine = false,
        onDismiss = onDismiss,
        onConfirm = { v -> if (v.isNotEmpty()) onConfirm(v) },
        label = label,
    )
}

/** 从一组选项里挑一个（新建节点的协议 / 代理组类型 / 集合类型…）。 */
@Composable
internal fun OptionPickerDialog(
    title: String,
    options: List<FormOption>,
    current: String? = null,
    summary: String? = null,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    WindowDialog(
        show = true,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (o in options) {
                    BasicComponent(
                        title = o.label,
                        summary = o.value.takeIf { it != o.label },
                        endActions = { if (o.value == current) Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) },
                        onClick = { onPick(o.value) },
                    )
                }
            }
            DialogButtons(onDismiss = onDismiss, confirmLabel = null)
        }
    }
}

/** 通用确认框（参考实现 confirmSheet）。 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = title,
        summary = text,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DialogButtons(onDismiss = onDismiss, confirmLabel = confirmLabel, onConfirm = onConfirm)
        }
    }
}

/** 删除被引用保护拦下时的说明（参考实现 reference-delete.js 的 showBlocked）。 */
@Composable
internal fun BlockedDeleteDialog(
    kindLabel: String,
    name: String,
    deletion: ConfigRefs.Deletion,
    onDismiss: () -> Unit,
) {
    val locations = deletion.locations
    WindowDialog(
        show = true,
        title = "无法删除$kindLabel",
        summary = "「${name.ifEmpty { "未命名" }}」：${deletion.reason}",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (loc in locations.take(100)) {
                    Text(text = loc, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                }
                if (locations.size > 100) DialogNote("…另有 ${locations.size - 100} 处，请修正后重新检查")
            }
            DialogNote("只检查当前配置草稿。不自动删除关联规则、成员或集合；外部订阅文件的内部内容、源码直接编辑不在此删除保护范围内。")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "知道了", onClick = onDismiss)
            }
        }
    }
}
