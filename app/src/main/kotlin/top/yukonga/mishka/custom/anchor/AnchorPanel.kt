package top.yukonga.mishka.custom.anchor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.custom.forms.MiniIconButton
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.sheetContentSafePadding
import top.yukonga.mishka.ui.util.sheetHeightTransition
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Location
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.scripta.editor.CodeEditorController
import top.yukonga.scripta.editor.text.TextPosition

/**
 * 锚点面板（自定义功能，见 mishka-custom/patches）：mihomo_box「工具页 → 锚点面板」的 Android 版。
 *
 * 它治的是同一类事故：订阅配置里 `&定义` 与 `*引用` 只存在于**原文**，YAML 一展开就再也看不见，
 * 而 mihomo 对悬空别名（引用了不存在的锚点）是直接拒绝启动的。面板把原文里的锚点图景摊开——
 * 每个锚点定义在哪、被谁继承、有没有人引用了却没定义——并给出行级的改名 / 改绑 / 删定义手术。
 *
 * 三条硬约定（与 mihomo_box 参考实现一致，也是本功能的全部安全边界）：
 *  1. **只动锚点语法**：`&` / `*` 只在「裸文本」区间（不在引号内、不在 `#` 注释后）才算锚点，
 *     手术只重写目标行/目标块的字符范围，其余字节一个都不动（含 CRLF、行尾空格、注释）。
 *  2. **改动先落在编辑器草稿里**：所有编辑都经 [CodeEditorController.replaceRange] 整篇替换，
 *     形成一个撤销单元；真正写盘仍走编辑器顶栏的保存（内核校验 + 失败回滚），所以面板写坏配置
 *     的唯一后果是「保存被拒」，文件不会被改坏。
 *  3. **悬空别名当场可见**：每次改动后立刻重扫，出现悬空引用会 toast 点名，面板里常驻列出。
 *
 * @param visible 是否展示面板（由编辑屏顶栏的入口按钮控制）
 * @param controller 编辑器控制器：面板读写它的文档与版本号
 * @param fileName 当前文件名，用来判定 YAML 与显示
 * @param onClose 关闭面板（调用方收起状态）
 */
@Composable
fun MishkaAnchorPanel(
    visible: Boolean,
    controller: CodeEditorController,
    fileName: String,
    onClose: () -> Unit,
) {
    // 面板只在打开时读文档：documentVersion 是快照 state，编辑器里改一笔、面板里改一笔都会重扫
    val version = if (visible) controller.documentVersion else -1
    val text = remember(version) { if (visible) controller.getText() else "" }
    val graph = remember(text) { if (text.isEmpty()) AnchorGraph() else AnchorScan.scan(text) }
    val isYaml = remember(fileName) { fileName.isEmpty() || isYamlFile(fileName) }

    var defEdit by remember { mutableStateOf<DefEditTarget?>(null) }
    var refEdit by remember { mutableStateOf<RefEditTarget?>(null) }
    var rename by remember { mutableStateOf<RenameTarget?>(null) }
    var drop by remember { mutableStateOf<AnchorInfo?>(null) }
    var creating by remember { mutableStateOf(false) }

    /**
     * 面板唯一的落地通道：整篇替换（一个撤销单元）→ 跳到改动处 → 一句结果提示。
     * 有悬空引用时提示会带上名字——这类错误 mihomo 会直接拒绝加载，必须在写盘前就被看见。
     */
    fun apply(newText: String, toast: String, revealLine1: Int? = null) {
        if (newText == text) {
            showToast(toast)
            return
        }
        // 终点取编辑器里旧文本的末位置（理由同 ConfigFormPanel 的 apply：新文本末行更短时会残留旧尾巴）
        val oldLines = controller.getText().split('\n')
        controller.replaceRange(
            TextPosition(0, 0),
            TextPosition(oldLines.lastIndex, oldLines.last().length),
            newText,
        )
        // 同一事件回调里紧接着跳转：keep-in-view 只对最终位置露出一次，不会先滚到文末
        if (revealLine1 != null) controller.jumpToLine((revealLine1 - 1).coerceAtLeast(0))
        // 两类「保存会被内核拒绝」的问题当场点名：悬空别名、以及被 `<<:` 合并却不再是映射的锚点。
        val after = AnchorScan.scan(newText)
        val dangling = after.danglers
        val badMerge = after.anchors.firstOrNull { anchor ->
            anchor.refs.any { it.merge } && !isMergeableAnchor(newText, anchor)
        }?.name
        val warning = when {
            dangling.isNotEmpty() -> "*${dangling.first().name} 现在没有定义"
            badMerge != null -> "&$badMerge 被 <<: 继承，但它已经不是映射，内核会拒绝"
            else -> null
        }
        showToast(
            message = if (warning == null) toast else "$toast（注意：$warning）",
            long = warning != null,
        )
    }

    fun locate(line1: Int) {
        onClose()
        controller.jumpToLine((line1 - 1).coerceAtLeast(0))
    }

    /**
     * 可换绑的目标锚点。两条硬规则都来自 YAML 本身（性质测试里被真解析器验证过）：
     *  1. **定义必须在引用行之前**——YAML 没有前向别名，指向后面的 `&定义` 直接解析失败；
     *  2. **`<<:` 合并继承只能指向映射**——指到标量/序列上会被解析器判成非法合并。
     */
    fun refCandidates(ref: AnchorRefLoc): List<String> =
        graph.anchors
            .filter { anchor -> anchor.defs.any { it.line < ref.line } }
            .filter { anchor -> !ref.merge || isMergeableAnchor(text, anchor) }
            .map { it.name }

    /** 定义块：面板上点「编辑」时按当前文本重新解析，保证拿到的是最新行范围。 */
    fun blockOf(name: String, line1: Int): DefBlock? {
        val lines = text.split('\n')
        val index = line1 - 1
        if (index !in lines.indices) return null
        return AnchorBlock.parse(lines, index, AnchorScan.blockEndIndex(lines, index, name), name)
    }

    WindowBottomSheet(
        show = visible,
        title = "锚点面板",
        onDismissRequest = onClose,
        endAction = {
            val dismiss = LocalDismissState.current
            IconButton(onClick = { dismiss?.invoke() }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .sheetContentSafePadding()
                .heightIn(min = 200.dp)
                .sheetHeightTransition(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isYaml) {
                    PanelHint("当前文件不是 YAML（.yaml / .yml），锚点面板只对 YAML 配置有意义。")
                } else {
                    PanelSummaryRow(
                        graph = graph,
                        onCreate = { creating = true },
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (graph.isEmpty) {
                            item(key = "empty") {
                                PanelHint(
                                    "还没有扫到 `&定义` / `*引用`。给一段共用参数挂上 `&名字`（例如 `proxies: &proxies`），" +
                                        "别处就能用 `<<: *proxies` 继承它；也可以点右上「新建」。",
                                )
                            }
                        }
                        items(items = graph.anchors, key = { "anchor:" + it.name }) { anchor ->
                            AnchorCard(
                                anchor = anchor,
                                defBlock = anchor.primaryDef?.let { d -> blockOf(anchor.name, d.line) },
                                onLocate = ::locate,
                                onEditDef = { line ->
                                    val block = blockOf(anchor.name, line)
                                    if (block == null) {
                                        // 定义块被改成拆不动的形态（flow 根、嵌套过深…）：不猜，交给编辑器
                                        showToast("这个定义块不是可可视化编辑的形态，请在编辑器里改这一段", long = true)
                                    } else {
                                        defEdit = DefEditTarget(anchor.name, block)
                                    }
                                },
                                onRename = {
                                    rename = RenameTarget(anchor, anchor.primaryDef?.let { text.split('\n').getOrNull(it.line - 1) }.orEmpty())
                                },
                                onDrop = { drop = anchor },
                                onEditRef = { ref -> refEdit = RefEditTarget(anchor.name, ref, refCandidates(ref)) },
                                onDeleteRef = { ref ->
                                    apply(
                                        newText = AnchorEdit.deleteLine(text, ref.line),
                                        toast = "已删除第 ${ref.line} 行（*${anchor.name} 少一处引用）",
                                    )
                                },
                            )
                        }
                        items(items = graph.danglers, key = { "dangling:" + it.name }) { dangling ->
                            DanglingCard(
                                dangling = dangling,
                                onLocate = ::locate,
                                onEditRef = { ref -> refEdit = RefEditTarget(dangling.name, ref, refCandidates(ref)) },
                                onDeleteRef = { ref ->
                                    apply(
                                        newText = AnchorEdit.deleteLine(text, ref.line),
                                        toast = "已删除第 ${ref.line} 行悬空引用",
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ==================== 子对话框：算好新文本再回面板统一落地 ====================

    creating.takeIf { it }?.let {
        AnchorCreateDialog(
            existingNames = graph.names,
            existingTopKeys = remember(text) { AnchorEdit.topLevelKeys(text) },
            onDismiss = { creating = false },
            onApply = { topKey, anchorName, rows ->
                apply(
                    newText = AnchorEdit.insertTopBlock(text, AnchorBlock.buildTopBlock(topKey, anchorName, rows)),
                    toast = "已新建 &$anchorName（插在文件头）",
                    revealLine1 = 1,
                )
                creating = false
            },
        )
    }

    defEdit?.let { target ->
        AnchorDefEditDialog(
            block = target.block,
            onDismiss = { defEdit = null },
            onApply = { newBlock ->
                val start = target.block.startLine1
                val end = start + target.block.lineCount - 1
                apply(
                    newText = AnchorEdit.replaceLines(text, start, end, newBlock),
                    toast = "已更新 &${target.name} 的定义块（${target.block.lineCount} 行）",
                    revealLine1 = start,
                )
                defEdit = null
            },
        )
    }

    refEdit?.let { target ->
        AnchorRefEditDialog(
            anchor = target.name,
            ref = target.ref,
            names = graph.names,
            candidates = target.candidates,
            onDismiss = { refEdit = null },
            onRebind = { newName ->
                when (val result = AnchorEdit.rebindRefLine(text, target.ref.line, newName)) {
                    null -> showToast("这一行不是 `*单个锚点` 的简单形态，请直接在编辑器里改", long = true)
                    else -> apply(
                        newText = result,
                        toast = if (newName == null) {
                            "已清除第 ${target.ref.line} 行的继承"
                        } else {
                            "第 ${target.ref.line} 行已改绑到 *$newName"
                        },
                        revealLine1 = target.ref.line,
                    )
                }
                refEdit = null
            },
            onApplyText = { line ->
                apply(
                    newText = AnchorEdit.replaceLines(text, target.ref.line, target.ref.line, line),
                    toast = "已改写第 ${target.ref.line} 行",
                    revealLine1 = target.ref.line,
                )
                refEdit = null
            },
        )
    }

    rename?.let { target ->
        AnchorRenameDialog(
            anchor = target.anchor,
            defLineRaw = target.defLine,
            knownNames = graph.names,
            existingTopKeys = remember(text) { AnchorEdit.topLevelKeys(text) },
            onDismiss = { rename = null },
            onApply = { newName, newKeyRaw ->
                var newText = text
                val notes = mutableListOf<String>()
                if (newName != null) {
                    newText = AnchorEdit.renameAnchor(newText, target.anchor.name, newName)
                    notes += "&${target.anchor.name} → &$newName（${target.anchor.refs.size} 处引用一起改）"
                }
                if (newKeyRaw != null) {
                    val line = target.anchor.primaryDef?.line ?: -1
                    val renamed = AnchorEdit.renameDefKey(newText, line, newKeyRaw)
                    if (renamed == null) {
                        showToast("定义行形态无法解析，请在编辑器里直接改这个键", long = true)
                        rename = null
                        return@AnchorRenameDialog
                    }
                    newText = renamed
                    notes += "条目名 → $newKeyRaw"
                }
                if (newText != text) {
                    apply(
                        newText = newText,
                        toast = "已改名：" + notes.joinToString("；"),
                        revealLine1 = target.anchor.primaryDef?.line,
                    )
                }
                rename = null
            },
        )
    }

    drop?.let { target ->
        AnchorDropDialog(
            anchor = target,
            onDismiss = { drop = null },
            onConfirm = {
                when (val result = AnchorEdit.dropAnchor(text, target.name)) {
                    is DropAnchorResult.KeptBlock -> apply(
                        newText = result.text,
                        toast = "已摘掉 &${target.name}：这个键是 mihomo 配置段，块体保留",
                        revealLine1 = target.primaryDef?.line,
                    )

                    is DropAnchorResult.RemovedBlock -> apply(
                        newText = result.text,
                        toast = "已删除 &${target.name} 的定义块（${result.removedLines} 行）",
                        revealLine1 = target.primaryDef?.line,
                    )

                    DropAnchorResult.NotFound -> showToast("找不到 &${target.name} 的定义行，可能已被改动", long = true)

                    is DropAnchorResult.Ambiguous -> showToast(
                        "&${target.name} 有 ${result.count} 处定义，无法确定删哪个，请在编辑器里处理",
                        long = true,
                    )
                }
                drop = null
            },
        )
    }
}

@Immutable
private data class DefEditTarget(val name: String, val block: DefBlock)

@Immutable
private data class RefEditTarget(val name: String, val ref: AnchorRefLoc, val candidates: List<String>)

@Immutable
private data class RenameTarget(val anchor: AnchorInfo, val defLine: String)

private fun isYamlFile(fileName: String): Boolean =
    fileName.endsWith(".yaml", ignoreCase = true) || fileName.endsWith(".yml", ignoreCase = true)

// ==================== 面板头部 ====================

@Composable
private fun PanelSummaryRow(graph: AnchorGraph, onCreate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (graph.anchors.isEmpty()) {
                    "配置里还没有锚点"
                } else {
                    buildString {
                        append("锚点 ${graph.anchors.size} 个")
                        val refs = graph.anchors.sumOf { it.refs.size }
                        if (refs > 0) append(" · 引用 $refs 处")
                        if (graph.danglers.isNotEmpty()) append(" · 悬空引用 ${graph.danglers.size} 处")
                    }
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            // 复用 mihomo_box 的约定：任何改动都先落在草稿里，保存时由内核校验把关
            Text(
                text = "改动先落在编辑器里，点顶栏保存才写文件",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        TextButton(
            text = "＋ 新建锚点",
            onClick = onCreate,
            minWidth = 0.dp,
            minHeight = 36.dp,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            textStyle = MiuixTheme.textStyles.button.copy(fontSize = 14.sp),
        )
    }
}

// ==================== 锚点卡 ====================

@Composable
private fun AnchorCard(
    anchor: AnchorInfo,
    defBlock: DefBlock?,
    onLocate: (Int) -> Unit,
    onEditDef: (Int) -> Unit,
    onRename: () -> Unit,
    onDrop: () -> Unit,
    onEditRef: (AnchorRefLoc) -> Unit,
    onDeleteRef: (AnchorRefLoc) -> Unit,
) {
    val def = anchor.primaryDef
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "&" + anchor.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            if (anchor.defs.size > 1) {
                Badge(text = "定义 ×${anchor.defs.size}", color = StatusColors.warning)
                Spacer(Modifier.width(8.dp))
            }
            if (anchor.refs.isEmpty()) {
                Badge(text = "无人引用", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                Badge(text = "继承 ${anchor.mergeCount} · 引用 ${anchor.aliasCount}", color = MiuixTheme.colorScheme.primary)
            }
        }
        if (def == null) {
            Text(
                text = "只见引用、没有定义：mihomo 会因 unknown anchor 拒绝加载",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = StatusColors.danger,
            )
        } else {
            BasicComponent(
                modifier = Modifier.padding(top = 4.dp),
                title = "定义在 L${def.line}",
                summary = def.path + (defBlock?.let { " · " + kindLabel(it) } ?: ""),
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                endActions = {
                    // 与 mihomo_box 面板一致：编辑入口用小图标按钮（✎），不再占一列文字
                    MiniIconButton(MiuixIcons.Edit, stringResource(R.string.common_edit)) { onEditDef(def.line) }
                },
                onClick = { onLocate(def.line) },
            )
            // 键跟值分开展示：映射 / 序列拆成参数行（左键右值），标量单独值行；拆不动的形态才给原文
            when {
                defBlock != null && defBlock.entries.isNotEmpty() -> DefKeyValueRows(defBlock)
                defBlock != null && defBlock.kind == DefKind.Scalar -> DefScalarRow(defBlock)
                else -> Text(
                    text = def.text.trim(),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        anchor.refs.forEach { ref ->
            BasicComponent(
                title = if (ref.merge) "继承 L${ref.line}" else "引用 L${ref.line}",
                summary = ref.path + " · " + ref.text.trim(),
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                endActions = {
                    MiniIconButton(MiuixIcons.Edit, stringResource(R.string.common_edit)) { onEditRef(ref) }
                    Spacer(Modifier.width(8.dp))
                    MiniIconButton(MiuixIcons.Delete, "删除这一行") { onDeleteRef(ref) }
                },
                onClick = { onLocate(ref.line) },
            )
        }
        // 卡片底部动作行：三个图标按钮（定位 / 改名 / 删除），与 mihomo_box 的行内图标按钮观感一致
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MiniIconButton(
                icon = MiuixIcons.Location,
                contentDescription = "定位",
                enabled = def != null,
                modifier = Modifier.weight(1f),
            ) { def?.let { onLocate(it.line) } }
            MiniIconButton(
                icon = MiuixIcons.Rename,
                contentDescription = "改名",
                modifier = Modifier.weight(1f),
            ) { onRename() }
            MiniIconButton(
                icon = MiuixIcons.Delete,
                contentDescription = stringResource(R.string.common_delete),
                enabled = def != null && anchor.refs.isEmpty(),
                modifier = Modifier.weight(1f),
            ) { onDrop() }
        }
        if (anchor.refs.isNotEmpty()) {
            Text(
                text = "要删定义得先清空引用：把引用改绑到别的锚点，或删掉引用行。",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun DanglingCard(
    dangling: DanglingAnchor,
    onLocate: (Int) -> Unit,
    onEditRef: (AnchorRefLoc) -> Unit,
    onDeleteRef: (AnchorRefLoc) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = CardDefaults.defaultColors(color = StatusColors.danger.copy(alpha = 0.10f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "*" + dangling.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = StatusColors.danger,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Badge(text = "悬空引用", color = StatusColors.danger)
        }
        Text(
            text = "配置里引用了 *${dangling.name}，却没有任何 `&${dangling.name}` 定义。可以改绑到已有锚点，或删掉这些引用行。",
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        dangling.refs.forEach { ref ->
            BasicComponent(
                title = "L${ref.line}",
                summary = ref.path + " · " + ref.text.trim(),
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                endActions = {
                    MiniIconButton(MiuixIcons.Edit, stringResource(R.string.common_edit)) { onEditRef(ref) }
                    Spacer(Modifier.width(8.dp))
                    MiniIconButton(MiuixIcons.Delete, "删除这一行") { onDeleteRef(ref) }
                },
                onClick = { onLocate(ref.line) },
            )
        }
    }
}

// ==================== 小组件 ====================

/** 定义块形态一行标签：映射 / 序列 / 标量 / 复杂，行内 flow 单独标出。 */
private fun kindLabel(block: DefBlock): String = when (block.kind) {
    DefKind.Map -> if (block.flowInline) "行内 flow 映射 ${block.entries.size} 项" else "映射 ${block.entries.size} 项"
    DefKind.Seq -> if (block.flowInline) "行内 flow 序列 ${block.entries.size} 项" else "序列 ${block.entries.size} 项"
    DefKind.Scalar -> "标量值"
    DefKind.Unknown -> "复杂形态"
}

/** 映射 / 序列定义拆成键值行：左列键名、右列值，键跟值不再糊成一条原文。 */
@Composable
private fun DefKeyValueRows(block: DefBlock) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        block.entries.take(6).forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (block.kind == DefKind.Seq) {
                    Text(
                        text = "-",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.width(14.dp),
                    )
                    Text(
                        text = e.value.ifEmpty { "（空）" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = e.keyRaw,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.42f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (e.nested) {
                            "（嵌套 ${e.endIdx - e.startIdx + 1} 行，编辑请切「文本」）"
                        } else {
                            e.value.ifEmpty { "（空）" }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.58f),
                    )
                }
            }
        }
        if (block.entries.size > 6) {
            Text(
                text = "… 还有 ${block.entries.size - 6} 项，点「编辑」看全部",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 标量定义：键名与值分两列显示。 */
@Composable
private fun DefScalarRow(block: DefBlock) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = block.keyRaw,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.42f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = block.headerValue.ifEmpty { "（空）" },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 11.sp,
        color = color,
    )
}

/**
 * 这个锚点能不能被 `<<:` 合并继承。只有「能确定不是映射」的形态才拦：
 * 序列、以及正文非 flow 映射的单值标量；拆不动的形态（Unknown）不拦——挨个拦会把
 * `d1: &d1 {a: 1, b: 2}` 这种合法的行内映射挡在外面。
 */
internal fun isMergeableAnchor(text: String, anchor: AnchorInfo): Boolean {
    val def = anchor.primaryDef ?: return false
    val lines = text.split('\n')
    val index = def.line - 1
    if (index !in lines.indices) return false
    val block = AnchorBlock.parse(lines, index, AnchorScan.blockEndIndex(lines, index, anchor.name), anchor.name)
        ?: return false
    return when (block.kind) {
        DefKind.Map -> true
        DefKind.Seq -> false
        DefKind.Scalar -> block.headerValue.trimStart().startsWith("{")
        DefKind.Unknown -> block.lines.drop(1).firstOrNull { it.isNotBlank() }?.trimStart()?.startsWith("{") == true
    }
}

@Composable
internal fun PanelHint(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
