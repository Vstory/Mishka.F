package top.yukonga.mishka.custom.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.platform.showToast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * P2 的七个「流程页」：出站代理 / 代理集合 / 代理组 / 路由规则 / 规则集合 / 子规则 / 流量隧道。
 *
 * 结构与行为对齐 mihomo_box WebUI（commit 657e7997）的 pages-flow.js / pages-config.js：列表页（新增 / 挪 / 删）→
 * 详情页（字段表）。字段表来自 [FormSpecsP2.kt]（`tools/forms/gen_specs.py` 生成，勿手改）；本文件只负责把字段表和
 * 文档里的某一项（`proxies[3]`、`proxy-providers.订阅A`…）接起来，所有写回仍走 [FormHost]。
 *
 * 与参考实现逐条对齐的页面级规则：
 *  - 节点：协议只在新建时选（编辑态只读）；传输层选择器停在「tcp（默认）」也显式写 `network: tcp`，切换时清掉其它
 *    `*-opts`，http 传输层补 `http-opts.method: GET`；TLS 开 → 没有 servername / sni 时补 `example.com`，关 → 删掉两者
 *    （anytls 内核默认开 TLS，只认显式 false）；链式出口排除自己；表单之外的键在「其他参数（YAML）」里整块编辑；
 *    改名同步全部代理组 `proxies` 里的引用。
 *  - 代理组：成员池 = 内置策略 + 代理组 + 节点（排除自己）；`use` 只能挑现有集合；成员留空 = 删键；
 *    类型可选「默认（不覆写）」= 删掉本地 type（锚点继承）；改名同步其它组的成员 + rules / sub-rules 的尾部策略。
 *  - 代理集合 / 规则集合：file 类型隐藏 http 专属字段并清掉 [FILE_HIDDEN_KEYS]；健康检查三态（默认 = 删整块）；
 *    改名同步代理组 `use` / `RULE-SET,名字`。
 *  - 删除出站代理 / 代理集合 / 代理组 / 规则集合前先查引用（[ConfigRefs.deletionState]）：有引用 → 「无法删除」说明；
 *    没有 → 确认后再查一次才删。
 *  - 规则：类型 / 匹配值 / 目标策略 / no-resolve 四件套 ⇄ 原文互转（[RuleText]），MATCH 没有匹配值。
 *  - 流量隧道：单行写法（`tcp/udp,监听,目标[,代理]`）与映射写法都认；映射写法的 `network` 按内核要求写成列表。
 */
@Composable
internal fun FlowFormPage(route: FormRoute, host: FormHost, nav: FormNav) {
    when (route) {
        is FormRoute.Proxies -> ProxyListPage(host, nav, route.seqPath)
        is FormRoute.Proxy -> ProxyDetailPage(host, nav, route.seqPath, route.index)
        FormRoute.Providers -> ProviderListPage(host, nav)
        is FormRoute.Provider -> ProviderDetailPage(host, nav, route.name)
        FormRoute.Groups -> GroupListPage(host, nav)
        is FormRoute.Group -> GroupDetailPage(host, nav, route.index)
        is FormRoute.Rules -> RuleListPage(host, route.seqPath, route.title)
        FormRoute.RuleProviders -> RuleProviderListPage(host, nav)
        is FormRoute.RuleProvider -> RuleProviderDetailPage(host, nav, route.name)
        FormRoute.SubRules -> SubRuleListPage(host, nav)
        FormRoute.Tunnels -> TunnelListPage(host, nav)
        is FormRoute.Tunnel -> TunnelDetailPage(host, nav, route.index)
        is FormRoute.Listener, is FormRoute.Section -> Unit
    }
}

// ---------------------------------------------------------------- 通用：序列列表页 / 映射列表页

/**
 * 序列（proxies / proxy-groups / tunnels / rules）的列表页：表头 + 每项一卡。
 *
 * 排序与操作全部对齐 mihomo_box WebUI（pages-flow.js / pages-config.js）：
 *  - **按住行首把手拖动排序**（`.drag-handle` 同款两列三点）：拖动中按 [DragSortState.order]
 *    实时换位（等价参考实现的 `insertBefore`），松手才把最终档位写回宿主一次；非被拖行补
 *    FLIP 滑移，被拖行即换即到位。「⋯」菜单里的上移 / 下移随之下线——有了把手，
 *    「在上方插入」也并入「新增 + 拖到位置」这一条路径；
 *  - 行尾是 ✎ 编辑 / × 删除两个图标按钮；`onDelete` 给了就走调用方的删除流程（引用保护），
 *    否则先弹一次确认再删（此前靠菜单里的两步确认）。
 */
@Composable
private fun SeqListPage(
    host: FormHost,
    seqPath: YPath,
    unit: String,
    emptyHint: String,
    headerActions: @Composable () -> Unit,
    rowTitle: (Int, YamlNode) -> String,
    rowSummary: (Int, YamlNode) -> String?,
    onOpen: (Int, YamlNode) -> Unit,
    onDelete: ((Int, YamlNode) -> Unit)? = null,
    // 规则列表额外显示行号（对齐 mihomo_box「路由规则」页的 `.rule-no`：把手右侧一个等宽数字胶囊）。
    // 顶层「路由规则」页才开（见 TOP_RULES_PATH 的说明）。
    // 规则自上而下匹配，序号 = 第几条；拖动中显示的是该行原来的序号，落位写回后随列表重新编号。
    numbered: Boolean = false,
    // 行文本限行（Int.MAX_VALUE = 不限，老调用点行为不变）。路由规则传 1：匹配值自带长列表时
    // 只要一行 + 省略号，见 RowCard 的 titleMaxLines / summaryMaxLines。
    rowMaxLines: Int = Int.MAX_VALUE,
) {
    val doc = host.doc
    val seq = doc.get(seqPath)
    val items: List<YamlNode> = if (seq != null && seq.kind == YamlNode.Kind.SEQ) seq.items else emptyList()
    val broken = seq != null && seq.kind != YamlNode.Kind.SEQ && !doc.isNullText(seq)
    var plainDelete by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val dragSort = rememberDragSortState(items.size) { from, to ->
        val label = items.getOrNull(from)?.let { rowTitle(from, it) } ?: unit
        host.moveItem(seqPath, from, to, label)
    }

    LazyColumn(
        state = listState,
        // 拖动一开始就关掉用户滚动：外层 scrollable 退出仲裁，抢流 / 整体上滑无从发生；
        // 边缘自动翻滚走 dispatchRawDelta（程序化滚动），不吃 userScrollEnabled
        userScrollEnabled = dragSort.dragging < 0,
        modifier = Modifier
            .fillMaxWidth()
            .then(dragSort.containerModifier { listState.dispatchRawDelta(it) }),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "hdr") {
            ListHeader(
                summary = "`${pathText(seqPath)}` · ${items.size} $unit · 按住行首把手拖动排序" +
                    (if (broken) " · 这个键不是列表（别名 / 映射 / 纯值），只能在编辑器里改" else ""),
                actions = headerActions,
            )
        }
        if (items.isEmpty() && !broken) {
            item(key = "empty") {
                Text(
                    text = emptyHint,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
        // 顺序由 DragSortState.order 定：拖动中它就是「列表内实时换位」的现场，不拖动时是恒等序；
        // 行内容与回调一律按原始下标取，换位不会让「编辑 / 删除」指到别的项上。
        items(dragSort.order, key = { it }) { i ->
            items.getOrNull(i)?.let { node ->
                DragSortRow(state = dragSort, index = i, modifier = dragSortItem(dragSort, i)) {
                    RowCard(
                        title = rowTitle(i, node),
                        summary = rowSummary(i, node),
                        onClick = { onOpen(i, node) },
                        titleMaxLines = rowMaxLines,
                        summaryMaxLines = rowMaxLines,
                        dragHandle = {
                            if (numbered) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DragHandle(dragSort, i)
                                    RuleIndexBadge(i + 1)
                                }
                            } else {
                                DragHandle(dragSort, i)
                            }
                        },
                        endActions = {
                            MiniIconButton(MiuixIcons.Edit, "编辑") { onOpen(i, node) }
                            Spacer(Modifier.width(8.dp))
                            MiniIconButton(MiuixIcons.Delete, "删除") {
                                if (onDelete != null) onDelete(i, node) else plainDelete = i
                            }
                        },
                    )
                }
            }
        }
    }

    if (plainDelete >= 0 && plainDelete < items.size) {
        val i = plainDelete
        val label = rowTitle(i, items[i])
        ConfirmDialog(
            title = "删除${unit.removePrefix("个").removePrefix("条")}",
            text = "删除「$label」（`${pathText(seqPath + i)}`）？这个操作只能靠编辑器撤销。",
            confirmLabel = "删除",
            onDismiss = { plainDelete = -1 },
            onConfirm = { plainDelete = -1; host.removeItem(seqPath, i, label) },
        )
    }
}

/**
 * 规则行的序号胶囊：对齐 mihomo_box「路由规则」页的 `.rule-no`——把手右侧、等宽数字、居中，
 * 淡底圆角，宽度按两位数字留（`min-width: 22px`）。规则顺序就是优先级，序号让「第几条」一眼可见。
 */
@Composable
private fun RuleIndexBadge(index: Int) {
    Box(
        modifier = Modifier
            .padding(start = 2.dp, end = 2.dp)
            .width(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 映射（proxy-providers / rule-providers / sub-rules）的列表页：按名字一卡。
 * 行尾 ✎ = **编辑所有配置**：进详情页改全部字段（与 mihomo_box 面板的 `.mini-btn ✎` → 全字段
 * 编辑弹层同口径，改名也在详情页里做）；× = 删除。映射无顺序，不给拖动把手。
 * 子规则没有详情页，额外给一个「改名」图标（[renameNote] / [onRename] 只有它传）。
 */
@Composable
private fun MapListPage(
    host: FormHost,
    mapPath: YPath,
    unit: String,
    emptyHint: String,
    headerActions: @Composable () -> Unit,
    rowSummary: (String, YamlNode?) -> String?,
    onOpen: (String) -> Unit,
    renameNote: String? = null,
    onRename: ((old: String, new: String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    val doc = host.doc
    val map = doc.get(mapPath)
    val entries: List<YamlEntry> = if (map != null && map.kind == YamlNode.Kind.MAP) map.entries.filter { !it.isMerge } else emptyList()
    val broken = map != null && map.kind != YamlNode.Kind.MAP && !doc.isNullText(map)
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "hdr") {
            ListHeader(
                summary = "`${pathText(mapPath)}` · ${entries.size} $unit" +
                    (if (broken) " · 这个键不是映射（别名 / 列表 / 纯值），只能在编辑器里改" else ""),
                actions = headerActions,
            )
        }
        if (entries.isEmpty() && !broken) {
            item(key = "empty") {
                Text(
                    text = emptyHint,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
        itemsIndexed(entries) { _, e ->
            RowCard(
                title = e.key,
                summary = rowSummary(e.key, e.node),
                onClick = { onOpen(e.key) },
                endActions = {
                    // ✎ = 编辑所有配置（点整卡同效）：详情页里改全部字段，改名也在那里
                    MiniIconButton(MiuixIcons.Edit, "编辑") { onOpen(e.key) }
                    if (onRename != null) {
                        Spacer(Modifier.width(8.dp))
                        MiniIconButton(MiuixIcons.Rename, "改名") { renaming = e.key }
                    }
                    Spacer(Modifier.width(8.dp))
                    MiniIconButton(MiuixIcons.Delete, "删除") {
                        if (onDelete != null) onDelete(e.key) else confirmDelete = e.key
                    }
                },
            )
        }
    }

    confirmDelete?.let { key ->
        ConfirmDialog(
            title = "删除" + unit.removePrefix("个").removePrefix("条").removePrefix("组"),
            text = "删除「$key」（`${pathText(mapPath + key)}`）？这个操作只能靠编辑器撤销。",
            confirmLabel = "删除",
            onDismiss = { confirmDelete = null },
            onConfirm = { confirmDelete = null; host.clear(mapPath + key, key) },
        )
    }
    val rename = onRename
    renaming?.let { key ->
        if (rename != null) {
            NameDialog(
                title = "改名",
                summary = "`${pathText(mapPath + key)}` · ${renameNote.orEmpty()}",
                initial = key,
                label = "新名字",
                onDismiss = { renaming = null },
                onConfirm = { new ->
                    renaming = null
                    if (new != key) rename(key, new)
                },
            )
        }
    }
}

/**
 * 详情页顶部的一卡：名字 + 路径 + 行尾图标按钮。
 *
 * 与 mihomo_box 面板一致：这里只放 ✎（改名 / 编辑）与 ×（删除）两个图标；**上移 / 下移已下线**——
 * 排序统一在列表页按住把手拖动，详情页再放一份只会出现「两处都能挪、两处结果不同」。
 */
@Composable
private fun DetailHeader(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
    endActions: (@Composable () -> Unit)? = null,
) {
    RowCard(
        title = title,
        summary = summary,
        onClick = onClick,
        endActions = { endActions?.invoke() },
    )
}

@Composable
private fun MissingItem(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(16.dp),
    )
}

private fun typeLabel(options: List<FormOption>, value: String?): String =
    options.firstOrNull { it.value == value }?.label ?: value.orEmpty().ifEmpty { "（无类型）" }

/** 序列里除第 `except` 项之外所有项的 name（查重名用；下标按序列位置算，没名字的项跳过）。 */
private fun namesExcept(doc: YamlDoc, seqPath: YPath, except: Int): Set<String> {
    val seq = doc.get(seqPath) ?: return emptySet()
    if (seq.kind != YamlNode.Kind.SEQ) return emptySet()
    val out = HashSet<String>()
    seq.items.forEachIndexed { i, item ->
        if (i != except) FormValues.scalarText(doc, doc.findEntry(item, "name")?.node)?.ifBlank { null }?.let { out.add(it) }
    }
    return out
}

private fun seqSize(doc: YamlDoc, seqPath: YPath): Int {
    val seq = doc.get(seqPath)
    return if (seq != null && seq.kind == YamlNode.Kind.SEQ) seq.items.size else 0
}

/** 展开视图（`<<: *锚点` 合并后）里某一项的某个键，给「源码没写本地 type 但继承到了」这类判断用。 */
private fun effectiveText(doc: YamlDoc, path: YPath): String? {
    var cur: Any? = PlainYaml.toPlain(doc)
    for (seg in path) {
        cur = when {
            seg is Int && cur is List<*> -> cur.getOrNull(seg)
            seg is String && cur is Map<*, *> -> cur[seg]
            else -> return null
        }
    }
    return when (cur) {
        null -> null
        is Map<*, *>, is List<*> -> null
        else -> cur.toString()
    }
}

// ---------------------------------------------------------------- 删除保护（参考实现 reference-delete.js）

/** 删除流程的状态：被引用拦下 / 等待确认。 */
private sealed class DeleteFlow(val kind: String, val name: String) {
    class Blocked(kind: String, name: String, val deletion: ConfigRefs.Deletion) : DeleteFlow(kind, name)
    class Confirm(kind: String, name: String) : DeleteFlow(kind, name)
}

private fun startDelete(doc: YamlDoc, kind: String, name: String): DeleteFlow {
    val d = ConfigRefs.deletionState(doc, kind, name)
    return if (d.blocked) DeleteFlow.Blocked(kind, name, d) else DeleteFlow.Confirm(kind, name)
}

/**
 * 「无法删除」说明 / 「确定删除？」确认两个弹层。确认时再查一次引用（期间草稿可能变了），
 * 序列类（proxies / proxy-groups）按再查得到的下标删，映射类按名字删。
 */
@Composable
private fun DeleteFlowDialogs(flow: DeleteFlow, host: FormHost, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val label = ConfigRefs.DELETE_LABELS[flow.kind] ?: flow.kind
    var state by remember(flow) { mutableStateOf(flow) }
    when (val s = state) {
        is DeleteFlow.Blocked -> BlockedDeleteDialog(kindLabel = label, name = s.name, deletion = s.deletion, onDismiss = onDismiss)
        is DeleteFlow.Confirm -> ConfirmDialog(
            title = "删除$label",
            text = "当前草稿未发现「${s.name}」的引用，确定删除？确认时将再次检查。",
            confirmLabel = "删除",
            onDismiss = onDismiss,
            onConfirm = {
                val again = ConfigRefs.deletionState(host.doc, s.kind, s.name)
                if (again.blocked) {
                    state = DeleteFlow.Blocked(s.kind, s.name, again)
                } else {
                    onDismiss()
                    val ok = if (again.index >= 0) host.removeItem(listOf(s.kind), again.index, "$label「${s.name}」")
                    else host.clear(listOf(s.kind, s.name), "$label「${s.name}」")
                    if (ok) onDeleted()
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 出站代理

private val PROXIES_PATH: YPath = listOf("proxies")

private fun proxyName(doc: YamlDoc, node: YamlNode): String? =
    FormValues.scalarText(doc, doc.findEntry(node, "name")?.node)?.ifBlank { null }

@Composable
private fun ProxyListPage(host: FormHost, nav: FormNav, seqPath: YPath) {
    val doc = host.doc
    var picker by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }
    var plainDelete by remember { mutableStateOf(-1) }
    val protectedList = seqPath == PROXIES_PATH
    SeqListPage(
        host = host,
        seqPath = seqPath,
        unit = "个节点",
        emptyHint = "还没有节点。点「新增节点」选协议，会按模板写入一项，再进详情页改服务器 / 端口 / 密码。",
        headerActions = {
            // 「解析」= 粘贴分享链接 / YAML 节点批量导入（参考实现 openProxyUriImport）
            TextButton(text = "解析", onClick = { importing = true })
            TextButton(text = "新增节点", onClick = { picker = true })
        },
        rowTitle = { i, node -> proxyName(doc, node) ?: "节点 #${i + 1}" },
        rowSummary = { _, node ->
            if (node.kind != YamlNode.Kind.MAP) "（别名 / 非映射项，只能在编辑器里改）"
            else {
                val type = FormValues.scalarText(doc, doc.findEntry(node, "type")?.node).orEmpty()
                val server = FormValues.scalarText(doc, doc.findEntry(node, "server")?.node)
                val port = FormValues.scalarText(doc, doc.findEntry(node, "port")?.node)
                buildString {
                    append(typeLabel(PROXY_TYPES, type))
                    if (!server.isNullOrEmpty()) append(" · ").append(server).append(if (!port.isNullOrEmpty()) ":$port" else "")
                    if (node.flow) append(" · 单行写法")
                }
            }
        },
        onOpen = { i, node ->
            if (node.kind == YamlNode.Kind.MAP) nav.push(FormRoute.Proxy(seqPath, i))
            else showToast("这一项不是映射（别名或纯值），请在编辑器里直接改", long = true)
        },
        onDelete = { i, node ->
            val name = proxyName(doc, node)
            if (protectedList && name != null) deleting = startDelete(doc, "proxies", name) else plainDelete = i
        },
    )
    if (picker) {
        OptionPickerDialog(
            title = "新建节点：选协议",
            summary = "按参考实现的模板写入一项，再进详情页改服务器 / 端口 / 密码",
            options = PROXY_TYPES,
            onDismiss = { picker = false },
            onPick = { type ->
                picker = false
                val tpl = LinkedHashMap<String, Any?>(PROXY_TEMPLATES[type] ?: mapOf("name" to "$type-out", "type" to type))
                tpl["name"] = uniqueName(tpl["name"]?.toString()?.ifBlank { null } ?: "$type-out", host.seqNames(seqPath))
                val index = seqSize(doc, seqPath)
                if (host.insertItem(seqPath, index, tpl, "节点 ${tpl["name"]}")) nav.push(FormRoute.Proxy(seqPath, index))
            },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = {}) }
    if (plainDelete >= 0) {
        val i = plainDelete
        ConfirmDialog(
            title = "删除节点",
            text = "删除 `${pathText(seqPath + i)}`？这个操作只能靠编辑器撤销。",
            confirmLabel = "删除",
            onDismiss = { plainDelete = -1 },
            onConfirm = { plainDelete = -1; host.removeItem(seqPath, i, "节点 #${i + 1}") },
        )
    }
    if (importing) {
        ProxyImportDialog(host = host, seqPath = seqPath, onDismiss = { importing = false })
    }
}

/**
 * 「解析节点」导入弹层（参考实现 proxy-uri-sheet.js）：URI 每行一条或整体 YAML 节点；
 * 成功项一次批量写入并从输入框移除，失败行留在框里方便修正重试。只加入当前草稿，不进代理组。
 */
@Composable
private fun ProxyImportDialog(host: FormHost, seqPath: YPath, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<String?>(null) }

    fun runImport() {
        val text = input
        val doc = host.doc
        // 保留名池（参考实现）：内置策略 + 节点 + 代理组；再加目标序列自己的名字防重
        val reserved = (BUILTIN_POLICIES.map { it.value } + host.seqNames(PROXIES_PATH) +
            host.seqNames(GROUPS_PATH) + host.seqNames(seqPath)).distinct()
        val result = try {
            ProxyUri.parseAll(text, reserved)
        } catch (e: ProxyUriError) {
            summary = e.message
            details = null
            return
        }
        if (result.proxies.isNotEmpty()) {
            val startIndex = seqSize(doc, seqPath)
            val ops = result.proxies.mapIndexed { i, p -> BatchOp.InsertItem(seqPath, startIndex + i, p) }
            val ok = host.batch(ops, "${result.proxies.size} 个节点", "已添加")
            if (!ok) {
                summary = "添加未应用，请先处理配置错误后重试；输入已保留。"
                details = null
                return
            }
            // 成功项移出输入框，重试失败项时不会再次追加已经导入的节点
            input = result.errors.joinToString("\n") { it.input }
            showToast("已添加 ${result.proxies.size} 个出站节点")
        }
        summary = "已添加 ${result.proxies.size} 个，失败 ${result.errors.size} 个，跳过重复 ${result.skipped} 个。" +
            if (result.proxies.isNotEmpty()) " 请保存配置。" else " 未修改配置。"
        details = (
            result.added.map { item ->
                "第 ${item.line} 行：已添加「${item.name}」" +
                    (if (item.renamed) "（重名已加序号）" else "") +
                    (if (item.warnings.isNotEmpty()) "\n  注意：${item.warnings.joinToString("；")}" else "")
            } + result.errors.map { "第 ${it.line} 行：${it.message}" }
            ).joinToString("\n").ifEmpty { null }
    }

    WindowDialog(
        show = true,
        title = "解析节点",
        summary = "`" + pathText(seqPath) + "` · 只添加到当前配置草稿，不覆盖已有节点、不自动加入代理组",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DialogNote("支持 URI（SS / VMess / VLESS / Trojan / Hysteria2 / TUIC）或 Clash/Mihomo YAML 节点（可不带 proxies:）。每批最多 500 个；同名自动加序号。")
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = "URI 每行一个，或粘贴 YAML 节点（可不带 proxies:）",
                useLabelAsPlaceholder = true,
                minLines = 5,
            )
            summary?.let { DialogNote(it) }
            details?.let { d ->
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(text = d, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
                TextButton(text = "解析并添加", onClick = { if (input.isNotBlank()) runImport() })
            }
        }
    }
}

/** 节点详情的小节（参考实现 editProxySheet 的分区）。`network` 是当前传输层取值（没有就按 tcp）。 */
private fun proxySections(type: String, network: String?): List<FormSection> {
    val features = PROXY_FEATURES[type] ?: emptySet()
    val per = PROXY_PER_TYPE[type].orEmpty()
    val perPaths = per.map { it.path }.toSet()
    val out = ArrayList<FormSection>()

    val basic = ArrayList<FormField>()
    if ("server" in features) basic.addAll(PROXY_BASE_FIELDS)
    basic.addAll(per)
    if (basic.isNotEmpty()) out.add(FormSection("基础", basic))

    if ("net" in features) {
        // network 选择器本身是详情页的专用行（切换要连带清理），这里只放该传输层的子字段
        val net = PROXY_NET_FIELDS[network?.ifBlank { null } ?: "tcp"].orEmpty()
        if (net.isNotEmpty()) out.add(FormSection(PROXY_SECTION_TITLES["net"] ?: "传输层", net))
    }
    if ("tls" in features) {
        val tls = PROXY_TLS_FIELDS.filter { f -> !(f.path == "tls" && "tlsbool" !in features) }
        out.add(FormSection(PROXY_SECTION_TITLES["tls"] ?: "TLS / Reality", tls))
    }
    if ("smux" in features) out.add(FormSection(PROXY_SECTION_TITLES["smux"] ?: "多路复用 smux", PROXY_SMUX_FIELDS))
    if ("tail" in features) {
        // 与协议专属字段撞 path 的尾字段只保留协议专属那份（direct 的 udp / ip-version 等）
        out.add(FormSection(PROXY_SECTION_TITLES["tail"] ?: "通用链式 / 拨号", PROXY_TAIL_FIELDS.filter { it.path !in perPaths }))
    }
    return out.filter { it.fields.isNotEmpty() }
}

/** 表单接管的顶层键（参考实现 rootKeys）：之外的键归「其他参数（YAML）」。 */
private fun proxyManagedKeys(type: String): Set<String> {
    val features = PROXY_FEATURES[type] ?: emptySet()
    val keys = LinkedHashSet<String>()
    keys.addAll(listOf("name", "type", "server", "port"))
    if ("net" in features) {
        keys.add("network")
        for (fields in PROXY_NET_FIELDS.values) for (f in fields) keys.add(f.path.substringBefore('.'))
    }
    for (f in PROXY_PER_TYPE[type].orEmpty()) keys.add(f.path.substringBefore('.'))
    for (f in PROXY_TLS_FIELDS) keys.add(f.path.substringBefore('.'))
    keys.add("smux")
    for (f in PROXY_TAIL_FIELDS) keys.add(f.path)
    return keys
}

private fun netOptsKey(network: String?): String? = when (network) {
    "ws" -> "ws-opts"
    "h2" -> "h2-opts"
    "grpc" -> "grpc-opts"
    "http" -> "http-opts"
    "xhttp" -> "xhttp-opts"
    else -> null
}

/** 一个映射条目的原文（块写法切行、去掉项的缩进；单行 {…} 写法按 `键: 值` 拼）。 */
private fun entryText(doc: YamlDoc, map: YamlNode, e: YamlEntry): String {
    val n = e.node
    if (map.flow || (n != null && (n.inline || n.kind == YamlNode.Kind.SCALAR || n.kind == YamlNode.Kind.RAW) && !n.multi && n.start == e.line)) {
        val v = if (n == null) "" else {
            val line = doc.lines[n.start]
            when {
                n.alias != null -> "*" + n.alias
                n.kind == YamlNode.Kind.MAP || n.kind == YamlNode.Kind.SEQ ->
                    if (n.flow) line.substring(n.flowStart.coerceIn(0, line.length), n.flowEnd.coerceIn(0, line.length)) else ""
                else -> line.substring(n.valueStart.coerceIn(0, line.length), n.valueEnd.coerceIn(0, line.length)).trim()
            }
        }
        return if (v.isEmpty()) "${YamlDoc.quoteSeg(e.key)}:" else "${YamlDoc.quoteSeg(e.key)}: $v"
    }
    val end = (n?.end ?: (e.line + 1)).coerceAtLeast(e.line + 1).coerceAtMost(doc.lines.size)
    val lines = doc.lines.subList(e.line, end)
    return lines.joinToString("\n") { ln ->
        val strip = minOf(e.keyIndent, ln.length - ln.trimStart().length)
        ln.substring(strip)
    }.trimEnd()
}

private val ANCHOR_OR_ALIAS_RE = Regex("(^|[\\s\\[{,])[&*][A-Za-z0-9_-]+")

/**
 * 解析器啃不动 / 将就着啃的节点：RAW（既不是块标量也不是别名）或者没闭合的 `{…` / `[…`（引擎为了容错会把它读到行尾）。
 * 「其他参数」是用户现敲的文本，这种写法不能猜着写回去。
 */
private fun hasBrokenNode(doc: YamlDoc, node: YamlNode?): Boolean {
    if (node == null) return false
    if (node.kind == YamlNode.Kind.RAW && !node.multi && node.alias == null) return true
    if (node.flow && (node.kind == YamlNode.Kind.MAP || node.kind == YamlNode.Kind.SEQ)) {
        val line = doc.lines.getOrNull(node.start) ?: return true
        val closer = if (node.kind == YamlNode.Kind.MAP) '}' else ']'
        if (node.flowEnd < 1 || node.flowEnd > line.length || line[node.flowEnd - 1] != closer) return true
    }
    return node.entries.any { hasBrokenNode(doc, it.node) } || node.items.any { hasBrokenNode(doc, it) }
}

@Composable
private fun ProxyDetailPage(host: FormHost, nav: FormNav, seqPath: YPath, index: Int) {
    val doc = host.doc
    val base: YPath = seqPath + index
    val node = doc.get(base)
    if (node == null || node.kind != YamlNode.Kind.MAP) {
        MissingItem("这一项已经不在了（或不是映射）")
        return
    }
    val name = FormValues.readRaw(doc, base + "name").orEmpty()
    val type = FormValues.readRaw(doc, base + "type").orEmpty()
    val features = PROXY_FEATURES[type] ?: emptySet()
    val known = type in PROXY_FEATURES
    val network = FormValues.readRaw(doc, base + "network")
    val sections = remember(type, network) { proxySections(type, network) }
    val managed = remember(type) { proxyManagedKeys(type) }
    val total = seqSize(doc, seqPath)
    val title = name.ifEmpty { "节点 #${index + 1}" }
    val protectedList = seqPath == PROXIES_PATH

    var renaming by remember { mutableStateOf(false) }
    var pickNet by remember { mutableStateOf(false) }
    var editOther by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }
    var plainDelete by remember { mutableStateOf(false) }

    val extras = node.entries.filter { !it.isMerge && it.key !in managed }

    // 传输层 / TLS 的连带处理（参考实现 syncNet / applyTlsLink）
    val intercept: FieldInterceptor = { field, path, value ->
        when {
            field.path == "tls" && "tlsbool" in features -> {
                val ops = ArrayList<BatchOp>()
                if (value == null) ops.add(BatchOp.Remove(path)) else ops.add(BatchOp.Set(path, value))
                val on = if (type == "anytls") value != false else value == true
                if (on) {
                    // 现值按展开视图判断：servername / sni 经锚点继承进来时不算缺失，
                    // 不再往条目里写 example.com 把继承的真值盖掉
                    if (!FormValues.hasEffective(doc, base + "servername") && !FormValues.hasEffective(doc, base + "sni")) {
                        ops.add(BatchOp.Set(base + "servername", "example.com"))
                    }
                } else {
                    ops.add(BatchOp.Remove(base + "servername"))
                    ops.add(BatchOp.Remove(base + "sni"))
                }
                host.batch(ops, field.label, reveal = path)
                true
            }
            else -> false
        }
    }

    fun setNetwork(v: String) {
        val ops = ArrayList<BatchOp>()
        ops.add(BatchOp.Set(base + "network", v))
        val keep = netOptsKey(v)
        for (k in NET_OPTS_KEYS) if (k != keep) ops.add(BatchOp.Remove(base + k))
        // method 经锚点继承时不算缺失：不再写本地 GET 顶掉继承的方法
        if (v == "http" && !FormValues.hasEffective(doc, base + "http-opts" + "method")) ops.add(BatchOp.Set(base + "http-opts" + "method", "GET"))
        host.batch(ops, "传输层 network", reveal = base + "network")
    }

    SectionsPage(
        sections = sections,
        base = base,
        host = host,
        intercept = intercept,
        dynamicOptions = { field, _ ->
            if (field.path == "dialer-proxy") host.candidates("outbound", exclude = setOf(name))
            else null
        },
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailHeader(
                    title = title,
                    summary = "`${pathText(base)}` · 点此改名" +
                        (if (protectedList) "（会同步代理组里的引用）" else "") +
                        (if (node.flow) " · 单行 {…} 写法，新增的键会接在同一行" else ""),
                    onClick = { renaming = true },
                    endActions = {
                        MiniIconButton(MiuixIcons.Edit, "改名") { renaming = true }
                        Spacer(Modifier.width(8.dp))
                        MiniIconButton(MiuixIcons.Delete, "删除") {
                            if (protectedList && name.isNotEmpty()) deleting = startDelete(doc, "proxies", name) else plainDelete = true
                        }
                    },
                )
                RowCard(
                    title = "协议",
                    summary = type.ifEmpty { "（没有 type）" } + (if (!known) "（非常见协议，仅基础字段 + 其他参数）" else "") +
                        " · 协议只在新建时选；要换协议请新建一个节点",
                    onClick = null,
                )
                if ("net" in features) {
                    RowCard(
                        title = "传输层 network",
                        summary = (if (network.isNullOrBlank() || network == "tcp") "tcp（默认）" else network) +
                            " · ws / h2 / grpc / http / xhttp；切换会清掉其它传输层的 *-opts",
                        onClick = { pickNet = true },
                        endActions = { TextButton(text = "选择", onClick = { pickNet = true }) },
                    )
                }
            }
        },
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(PROXY_SECTION_TITLES["other"] ?: "其他参数（YAML，可选）")
                RowCard(
                    title = if (extras.isEmpty()) "没有表单之外的字段" else extras.joinToString(", ") { it.key },
                    summary = "表单未覆盖的官方字段在此补充（YAML 键值），确定后整块并入这一项",
                    onClick = { editOther = true },
                    endActions = { MiniIconButton(MiuixIcons.Edit, "编辑") { editOther = true } },
                )
            }
        },
    )

    if (renaming) {
        NameDialog(
            title = "节点名",
            summary = if (protectedList) "改名会同步所有代理组 proxies 里的引用（规则里直接写节点名的不会动）" else "内联集合里的节点：只改这一项的 name",
            initial = name,
            label = "节点名（唯一）",
            onDismiss = { renaming = false },
            onConfirm = { new ->
                renaming = false
                if (new == name) return@NameDialog
                if (new in namesExcept(doc, seqPath, index)) {
                    showToast("已存在同名节点", long = true)
                    return@NameDialog
                }
                if (name.isEmpty() || !protectedList) {
                    host.set(base + "name", new, "节点名")
                } else {
                    val plan = RenameSync.proxy(doc, seqPath, index, name, new)
                    if (plan.blocked != null) showToast(plan.blocked, long = true)
                    else if (host.batch(plan.ops, "节点名", "已改名", base + "name") && plan.synced > 0) showToast("已重命名，并同步更新 ${plan.synced} 处代理组引用")
                }
            },
        )
    }
    if (pickNet) {
        OptionPickerDialog(
            title = "传输层 network",
            options = PROXY_NETWORKS,
            current = network?.ifBlank { null } ?: "tcp",
            onDismiss = { pickNet = false },
            onPick = { v -> pickNet = false; setNetwork(v) },
        )
    }
    if (editOther) {
        TextValueDialog(
            title = PROXY_SECTION_TITLES["other"] ?: "其他参数（YAML，可选）",
            summary = "`${pathText(base)}` · 表单之外的字段，如\nglobal-padding: true\nauthenticated-length: true",
            desc = "确定后：这里删掉的键会从节点里删掉，写的键会写入 / 覆盖；不支持锚点与别名",
            current = extras.joinToString("\n") { entryText(doc, node, it) },
            multiLine = true,
            onDismiss = { editOther = false },
            onConfirm = { text ->
                editOther = false
                val parsed = if (text.isBlank()) null else YamlDoc.parse(text)
                val root = parsed?.root
                when {
                    text.isNotBlank() && (root == null || root.kind != YamlNode.Kind.MAP || root.entries.any { it.key == "__seq__" || it.key == "__scalar__" || it.isMerge }) ->
                        showToast("其他参数 YAML 错误：需要是键值对象", long = true)
                    ANCHOR_OR_ALIAS_RE.containsMatchIn(text) -> showToast("其他参数里不能用锚点 / 别名（&name / *name），请在编辑器里直接改", long = true)
                    parsed != null && hasBrokenNode(parsed, root) -> showToast("其他参数 YAML 错误：有解析不了的 { } / [ ] 写法，请检查括号是否配对", long = true)
                    else -> {
                        val plain: Map<String, Any?> = if (parsed == null) emptyMap() else PlainYaml.toPlain(parsed)
                        val ops = ArrayList<BatchOp>()
                        for (e in extras) if (e.key !in plain) ops.add(BatchOp.Remove(base + e.key))
                        for ((k, v) in plain) ops.add(BatchOp.Set(base + k, v))
                        if (ops.isEmpty()) showToast("其他参数没有变化")
                        else host.batch(ops, "其他参数", reveal = base)
                    }
                }
            },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = { nav.pop() }) }
    if (plainDelete) {
        ConfirmDialog(
            title = "删除节点",
            text = "删除 `${pathText(base)}`？这个操作只能靠编辑器撤销。",
            confirmLabel = "删除",
            onDismiss = { plainDelete = false },
            onConfirm = { plainDelete = false; if (host.removeItem(seqPath, index, title)) nav.pop() },
        )
    }
}

// ---------------------------------------------------------------- 代理集合 proxy-providers

private val PROVIDERS_PATH: YPath = listOf("proxy-providers")

/** 新建代理集合（参考实现 addSubSheet）：PROVIDER_DEFAULT + 类型 / 链接；file 补默认 path 并去掉远程字段；inline 预置空 payload。 */
private fun providerTemplate(name: String, type: String, url: String): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>(PROVIDER_DEFAULT)
    m["type"] = type
    m["url"] = url
    when (type) {
        "file" -> {
            m["path"] = "./proxies/${safeFileStem(name, "provider")}.yaml"
            for (k in FILE_HIDDEN_KEYS["sub"].orEmpty()) m.remove(k)
        }
        "inline" -> {
            m.remove("url"); m.remove("path"); m.remove("interval")
            m["payload"] = emptyList<Any?>()
        }
    }
    if (m["url"] == "") m.remove("url")
    if (m["path"] == "") m.remove("path")
    return m
}

/** 新建集合的小表单：名字 + 类型 + （http）链接。 */
@Composable
private fun NewProviderDialog(
    title: String,
    nameLabel: String,
    types: List<FormOption>,
    existing: Collection<String>,
    urlLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, url: String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
    validate: (type: String, url: String) -> String? = { _, _ -> null },
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(types.first().value) }
    var url by remember { mutableStateOf("") }
    var pickType by remember { mutableStateOf(false) }
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = nameLabel,
                singleLine = true,
            )
            BasicComponent(
                title = "类型",
                summary = typeLabel(types, type),
                endActions = { Text(text = if (pickType) "收起" else "选择", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) },
                onClick = { pickType = !pickType },
            )
            if (pickType) {
                for (o in types) BasicComponent(title = o.label, onClick = { type = o.value; pickType = false })
            }
            if (type == "http") {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    label = urlLabel,
                    singleLine = true,
                )
            }
            extra?.invoke()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "创建", onClick = {
                    val n = name.trim()
                    val u = url.trim()
                    val err = validate(type, u)
                    when {
                        n.isEmpty() -> showToast("请填写名称")
                        n in existing -> showToast("已经有叫「$n」的了，换个名字", long = true)
                        err != null -> showToast(err, long = true)
                        else -> onConfirm(n, type, u)
                    }
                })
            }
        }
    }
}

@Composable
private fun ProviderListPage(host: FormHost, nav: FormNav) {
    val doc = host.doc
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }
    MapListPage(
        host = host,
        mapPath = PROVIDERS_PATH,
        unit = "个订阅",
        emptyHint = "还没有代理集合。http 订阅 / 本地文件 / 内联节点三种类型，新建后进详情页改链接与健康检查。",
        headerActions = { TextButton(text = "新增集合", onClick = { creating = true }) },
        rowSummary = { name, node ->
            if (node == null || node.kind != YamlNode.Kind.MAP) "（不是映射，只能在编辑器里改）"
            else {
                // 本地行优先；纯经 <<: *锚点 继承来的参数走展开视图，列表摘要不丢配置参数
                val type = FormValues.scalarText(doc, doc.findEntry(node, "type")?.node)
                    ?: FormValues.effectiveText(doc, PROVIDERS_PATH + name + "type")
                    ?: ""
                val src = FormValues.scalarText(doc, doc.findEntry(node, "url")?.node)
                    ?: FormValues.scalarText(doc, doc.findEntry(node, "path")?.node)
                    ?: FormValues.effectiveText(doc, PROVIDERS_PATH + name + "url")
                    ?: FormValues.effectiveText(doc, PROVIDERS_PATH + name + "path")
                buildString {
                    append(typeLabel(PROVIDER_TYPES, type))
                    if (type == "inline") append(" · ").append(FormValues.count(doc, PROVIDERS_PATH + name + "payload")).append(" 个节点")
                    else if (!src.isNullOrEmpty()) append(" · ").append(src.take(60))
                }
            }
        },
        onOpen = { name -> nav.push(FormRoute.Provider(name)) },
        // ✎ = 编辑所有配置（详情页有全部字段与「改名」，与 mihomo_box 面板一致）
        onDelete = { name -> deleting = startDelete(doc, "proxy-providers", name) },
    )
    if (creating) {
        NewProviderDialog(
            title = "新建代理集合",
            nameLabel = "集合名（proxy-groups 的 use 里引用它）",
            types = PROVIDER_TYPES,
            existing = host.mapKeys(PROVIDERS_PATH),
            urlLabel = "订阅链接 url",
            onDismiss = { creating = false },
            validate = { type, url -> if (type == "http" && url.isEmpty()) "http 类型需要填写订阅链接" else null },
            onConfirm = { name, type, url ->
                creating = false
                if (host.set(PROVIDERS_PATH + name, providerTemplate(name, type, url), "代理集合 $name")) nav.push(FormRoute.Provider(name))
            },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = {}) }
}

private fun renameProvider(host: FormHost, old: String, new: String): Boolean {
    val plan = RenameSync.provider(host.doc, old, new)
    if (plan.blocked != null) { showToast(plan.blocked, long = true); return false }
    val ok = host.batch(plan.ops, "代理集合 $old", "已改名", PROVIDERS_PATH + new)
    if (ok && plan.synced > 0) showToast("已重命名，并同步更新 ${plan.synced} 处代理组 use 引用")
    return ok
}

/** 读 override.override-expr（含锚点继承回落）：见 [FormValues.exprItems]；这里只取列表部分。 */
private fun exprItems(doc: YamlDoc, base: YPath): List<String> = FormValues.exprItems(doc, base).first

/**
 * 写 override-expr。存量配置里 `override` 常是单行 flow 映射（如
 * `override: { additional-prefix: ..., <<: *host }`），引擎不会把装不下一行的列表塞进 flow
 * （[YamlPatch.flowSet] 直接拒绝，旧代码只会弹「这个位置不能这样写」）——r19 起做行内原位替换：
 * **单行 flow 保持单行**（对照 mihomo_box 的范围替换语义），其它键与 `<<: *锚点` 逐字保留；
 * 块式 / 缺失则直接交引擎。清空且值是纯继承时没有本地行可删，提示去「YAML 锚点」区解除继承。
 */
private fun commitExprList(host: FormHost, doc: YamlDoc, base: YPath, list: List<String>): Boolean {
    val path = base + "override" + "override-expr"
    // 表达式经 `<<: *锚点` 继承时优先写穿到锚点定义（对齐参考实现：共享的 override-expr
    // 改一处、所有继承条目一起生效）；本地已有 override-expr 行则照旧走本地覆写
    if (list.isNotEmpty() && AnchorInheritance.writeThroughTarget(doc, path) != null) {
        return host.set(path, list, "override-expr")
    }
    val ov = doc.get(base + "override")
    if (ov != null && ov.kind == YamlNode.Kind.MAP && ov.flow) {
        val text = AnchorInheritance.setFlowMapListField(doc, base + "override", "override-expr", list)
        if (text == null) {
            showToast("override 是跨多行的 flow 映射，请在编辑器里直接改", long = true)
            return false
        }
        return host.applyRawText(text, "override-expr", ov.start + 1)
    }
    if (list.isEmpty()) {
        return when {
            doc.get(path) != null -> host.clear(path, "override-expr")
            exprItems(doc, base).isNotEmpty() -> {
                showToast("表达式来自锚点继承：要清除请在「YAML 锚点」区解除继承，或改锚点定义", long = true)
                false
            }
            else -> { showToast("未设置表达式"); false }
        }
    }
    return host.set(path, list, "override-expr")
}

@Composable
private fun ProviderDetailPage(host: FormHost, nav: FormNav, name: String) {
    val doc = host.doc
    val base: YPath = PROVIDERS_PATH + name
    val node = doc.get(base)
    if (node == null || node.kind != YamlNode.Kind.MAP) {
        MissingItem("这个集合已经不在了（或不是映射）")
        return
    }
    val type = FormValues.readRaw(doc, base + "type")?.ifBlank { null } ?: effectiveText(doc, base + "type") ?: "http"
    var renaming by remember { mutableStateOf(false) }
    var hcPick by remember { mutableStateOf(false) }
    var exprEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }
    // 启用状态按展开视图读：health-check 常整块经 `<<: *锚点` 继承进来，按本地行判断会误显示成
    // 「默认（不覆写）」，随后补默认值时又把继承的 url / interval 整块盖掉
    val hcEnable = FormValues.effectiveBool(doc, base + "health-check" + "enable")
    val hcHas = FormValues.hasEffective(doc, base + "health-check" + "enable")
    val hcLocal = FormValues.hasValue(doc, base + "health-check" + "enable")

    // 切类型：file 去掉远程字段；inline 预置 payload（参考实现 cleanProviderForSave / 新建逻辑）
    val intercept: FieldInterceptor = { field, path, value ->
        if (field.path == "type" && value is String) {
            val ops = ArrayList<BatchOp>()
            ops.add(BatchOp.Set(path, value))
            if (value == "file") {
                for (k in FILE_HIDDEN_KEYS["sub"].orEmpty()) ops.add(BatchOp.Remove(base + k))
                // path 经锚点继承时不算缺失：不再写默认路径顶掉继承值
                if (!FormValues.hasEffective(doc, base + "path")) ops.add(BatchOp.Set(base + "path", "./proxies/${safeFileStem(name, "provider")}.yaml"))
            }
            if (value == "inline" && doc.get(base + "payload") == null) ops.add(BatchOp.Set(base + "payload", emptyList<Any?>()))
            host.batch(ops, field.label, reveal = path)
            true
        } else false
    }

    SectionsPage(
        sections = PROXY_PROVIDER_SECTIONS,
        base = base,
        host = host,
        onlyType = type,
        intercept = intercept,
        footer = {
            AnchorSectionCard(host, "proxy-providers", base)
        },
        header = {
            DetailHeader(
                title = name,
                summary = "`${pathText(base)}` · ${typeLabel(PROVIDER_TYPES, type)} · 点此改名（会同步代理组 use）",
                onClick = { renaming = true },
                endActions = {
                    MiniIconButton(MiuixIcons.Edit, "改名") { renaming = true }
                    Spacer(Modifier.width(8.dp))
                    MiniIconButton(MiuixIcons.Delete, "删除") { deleting = startDelete(doc, "proxy-providers", name) }
                },
            )
        },
        customRow = { row ->
            when (row.kind) {
                "provider-payload" -> if (type == "inline") {
                    RowCard(
                        title = "内联节点 payload",
                        summary = "${FormValues.count(doc, base + "payload")} 个节点 · 和「出站代理」同一套节点编辑器",
                        onClick = { nav.push(FormRoute.Proxies(base + "payload", "$name · payload")) },
                        endActions = { Text(text = "打开", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) },
                    )
                }
                "provider-health-enable" -> RowCard(
                    title = "启用健康检查",
                    summary = (if (!hcHas) "默认（不覆写）" else (if (hcEnable == true) "开" else "关") + (if (!hcLocal) "（继承）" else "")) +
                        (if (hcHas && !hcLocal) " · 值来自锚点继承，改动会写穿到锚点定义"
                        else " · 选「默认（不覆写）」将删除整个 health-check 配置块"),
                    onClick = { hcPick = true },
                    endActions = { TextButton(text = "选择", onClick = { hcPick = true }) },
                )
                "override-expr" -> {
                    // 本地行 + 展开视图（锚点 `<<:` / 别名）一起读；继承来的值标「继承」，编辑写穿到锚点定义
                    val (items, inherited) = FormValues.exprItems(doc, base)
                    RowCard(
                        title = when {
                            items.isEmpty() -> "未设置表达式"
                            inherited -> "${items.size} 条表达式（继承）"
                            else -> "${items.size} 条表达式"
                        },
                        summary = (if (items.isEmpty()) "" else items.joinToString(" · ").take(80) + " · ") +
                            "yq v4 风格子集，逐条顺序执行，作用于单个节点；支持路径赋值 = / |= / del() / select" +
                            (if (inherited && items.isNotEmpty()) "；当前值来自锚点继承，编辑将写穿到锚点定义（所有继承它的条目一起生效）" else ""),
                        onClick = { exprEditor = true },
                        endActions = { MiniIconButton(MiuixIcons.Edit, "编辑") { exprEditor = true } },
                    )
                }
                "provider-file-ops" -> if (type == "file") {
                    ProviderFileOpsRow(
                        host = host,
                        base = base,
                        kind = "sub",
                        defaultPath = "./proxies/${safeFileStem(name, "provider")}.yaml",
                        newFileText = "proxies:\n  # - { name: node1, type: ss, server: example.com, port: 8388, cipher: aes-256-gcm, password: xxx }\n",
                    )
                }
            }
        },
    )
    if (renaming) {
        NameDialog(
            title = "改名",
            summary = "`${pathText(base)}` · 会同步代理组 use 里的引用",
            initial = name,
            label = "新名字",
            onDismiss = { renaming = false },
            onConfirm = { new ->
                renaming = false
                if (new != name && renameProvider(host, name, new)) nav.replaceTop(FormRoute.Provider(new))
            },
        )
    }
    if (hcPick) {
        TriBoolDialog(
            title = "启用健康检查",
            desc = "开启时自动补官方默认值（仅补缺失项）：没有 url 内核不会真正测速",
            current = if (hcHas) hcEnable else null,
            emptySummary = "删除整个 health-check 配置块（连同 url / interval / timeout / lazy / expected-status）",
            onDismiss = { hcPick = false },
            onPick = { v ->
                hcPick = false
                if (v == null) {
                    when {
                        doc.get(base + "health-check") != null -> host.clear(base + "health-check", "健康检查")
                        hcHas -> showToast("health-check 来自锚点继承，本地没有可删的块：请在下方「锚点与继承」里解除继承后再选「默认（不覆写）」", long = true)
                        else -> showToast("health-check 本来就没有设置")
                    }
                } else {
                    val ops = ArrayList<BatchOp>()
                    // 生效值已经是目标值就不写：不给继承块凭空物化一份本地副本
                    if (hcEnable != v) ops.add(BatchOp.Set(base + "health-check" + "enable", v))
                    if (v) {
                        for ((k, dv) in HC_DEFAULTS) {
                            // 现值按展开视图读：继承来的 url / interval 不算「缺失」，绝不拿官方默认值盖掉锚点里的配置
                            val cur = FormValues.effectiveText(doc, base + "health-check" + k)
                            if (cur.isNullOrEmpty()) ops.add(BatchOp.Set(base + "health-check" + k, dv))
                        }
                    }
                    if (ops.isEmpty()) showToast("健康检查没有变化（当前${if (hcHas && !hcLocal) "继承值" else "值"}已生效）")
                    else host.batch(ops, "启用健康检查", reveal = base + "health-check")
                }
            },
        )
    }
    if (exprEditor) {
        ExprListDialog(
            items = exprItems(doc, base),
            onDismiss = { exprEditor = false },
            onCommit = { list -> commitExprList(host, doc, base, list) },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = { nav.pop() }) }
}

// ---- override-expr（参考实现 exprSheet：可视化 ⇄ 文本）

private class ExprParts(val kind: String, val select: String, val path: String, val op: String, val value: String)

private val EXPR_DEL_RE = Regex("^del\\(\\s*(\\.[\\s\\S]*?)\\s*\\)$")
private val EXPR_SET_RE = Regex("^(\\(select\\(([\\s\\S]*)\\) \\| )?([^\\s=(]+)\\s*(\\|=|=)\\s*([\\s\\S]+)$")

private fun exprParse(line: String): ExprParts? {
    val s = line.trim()
    EXPR_DEL_RE.find(s)?.let { return ExprParts("del", "", it.groupValues[1].trim(), "=", "") }
    val m = EXPR_SET_RE.find(s) ?: return null
    var path = m.groupValues[3]
    if (path.isEmpty()) return null
    if (m.groups[1] != null && path.endsWith(")")) path = path.dropLast(1)
    return ExprParts("set", m.groupValues[2].trim(), path, m.groupValues[4], m.groupValues[5].trim())
}

private fun exprBuild(p: ExprParts): String? {
    val path = p.path.trim()
    if (p.kind == "del") return if (path.isEmpty()) null else "del($path)"
    val v = p.value.trim()
    if (path.isEmpty() || v.isEmpty()) return null
    val sel = p.select.trim()
    return if (sel.isNotEmpty()) "(select($sel) | $path) ${p.op} $v" else "$path ${p.op} $v"
}

@Composable
private fun ExprListDialog(items: List<String>, onDismiss: () -> Unit, onCommit: (List<String>) -> Boolean) {
    var editing by remember { mutableStateOf<Int?>(null) }   // -1 = 新建
    // 本地镜像：写回成功后立即同步显示——不依赖整篇替换后的重组时机，快捷填入立刻可见。
    // onCommit 返回 false（写被拒）时镜像不动，显示始终等于草稿真实内容；
    // 外层 items 变化（写回成功后文档换新版本）时以文档为准回填。
    var local by remember { mutableStateOf(items) }
    LaunchedEffect(items) { local = items }
    WindowDialog(
        show = true,
        title = "override-expr",
        summary = "可视化新建 / 编辑：赋值（可选 select 条件 + 字段路径 + 值）/ 删除字段；超出范围的形态自动降级文本模式。每次改动立即写回草稿。",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "常用表达式（点击填入，可继续编辑 / 删除）：",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for ((label, exprs) in EXPR_PRESETS) {
                    BasicComponent(
                        title = label,
                        summary = exprs.joinToString(" ; ").take(80),
                        onClick = {
                            val a = ArrayList(local)
                            var added = 0
                            for (e in exprs) if (e !in a) { a.add(e); added++ }
                            if (added == 0) {
                                showToast("这些表达式已在列表中")
                            } else if (onCommit(a)) {
                                local = a
                                showToast("已填入 $added 条表达式")
                            }
                            // 写被拒时 onCommit 已给出原因提示，镜像与列表保持原状
                        },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (local.isEmpty()) DialogNote("未设置表达式（点「＋ 添加表达式」可视化新建，或点上方快捷按钮填入）")
                local.forEachIndexed { i, ex ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ex,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        // 行尾 ✎ 编辑 / × 删除：与 mihomo_box 面板的 `.mini-btn` 同款小图标按钮
                        MiniIconButton(MiuixIcons.Edit, "编辑") { editing = i }
                        Spacer(Modifier.width(8.dp))
                        MiniIconButton(MiuixIcons.Delete, "删除") {
                            val f = local.filterIndexed { j, _ -> j != i }
                            if (onCommit(f)) local = f
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "＋ 添加表达式", onClick = { editing = -1 })
                Spacer(Modifier.width(8.dp))
                TextButton(text = "完成", onClick = onDismiss)
            }
        }
    }
    editing?.let { idx ->
        ExprEditDialog(
            initial = if (idx >= 0) local.getOrNull(idx).orEmpty() else "",
            title = if (idx >= 0) "编辑表达式 #${idx + 1}" else "新建表达式",
            onDismiss = { editing = null },
            onConfirm = { v ->
                editing = null
                val a = ArrayList(local)
                if (idx < 0) a.add(v) else a[idx] = v
                if (onCommit(a)) local = a
            },
        )
    }
}

@Composable
private fun ExprEditDialog(initial: String, title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parsed = remember(initial) { if (initial.isEmpty()) ExprParts("set", "", "", "=", "") else exprParse(initial) }
    var visual by remember { mutableStateOf(parsed != null) }
    var kind by remember { mutableStateOf(parsed?.kind ?: "set") }
    var select by remember { mutableStateOf(parsed?.select.orEmpty()) }
    var path by remember { mutableStateOf(parsed?.path.orEmpty()) }
    var op by remember { mutableStateOf(parsed?.op ?: "=") }
    var value by remember { mutableStateOf(parsed?.value.orEmpty()) }
    var text by remember { mutableStateOf(initial) }
    val preview = exprBuild(ExprParts(kind, select, path, op, value))
    val previewText = when {
        path.trim().isNotEmpty() && !path.trim().startsWith(".") -> "⚠ 字段路径须以 . 开头"
        preview != null -> preview
        else -> "⚠ 内容不完整：" + (if (kind == "del") "请填写字段路径" else "请填写字段路径和值")
    }
    WindowDialog(
        show = true,
        title = title,
        summary = if (parsed == null) "该表达式形态超出可视化范围（链式 // 等高级语法），请直接用文本编辑。" else null,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (parsed != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        text = if (visual) "切换为文本" else "切换为可视化",
                        minWidth = 0.dp, minHeight = 0.dp,
                        onClick = {
                            if (visual) {
                                text = preview ?: text
                                visual = false
                            } else {
                                val p = if (text.trim().isEmpty()) ExprParts("set", "", "", "=", "") else exprParse(text.trim())
                                if (p == null) showToast("当前文本超出可视化范围，请继续用文本编辑；内容未改动", long = true)
                                else { kind = p.kind; select = p.select; path = p.path; op = p.op; value = p.value; visual = true }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (visual) {
                BasicComponent(
                    title = "操作类型",
                    summary = if (kind == "del") "删除字段" else "赋值",
                    endActions = {
                        TextButton(text = if (kind == "del") "改为赋值" else "改为删除字段", minWidth = 0.dp, minHeight = 0.dp, onClick = { kind = if (kind == "del") "set" else "del" })
                    },
                )
                if (kind == "set") {
                    TextField(value = select, onValueChange = { select = it }, modifier = Modifier.fillMaxWidth(), label = "条件 select（可选）：.port == 443，留空 = 对全部节点", singleLine = true)
                }
                TextField(
                    value = path, onValueChange = { path = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    label = if (kind == "del") "字段路径：.skip-cert-verify" else "字段路径：.name / .tls / .[\"ws-opts\"].headers.Host", singleLine = true,
                )
                if (kind == "set") {
                    BasicComponent(
                        title = "操作符",
                        summary = if (op == "|=") "|= 追加/合并" else "= 赋值",
                        endActions = { TextButton(text = "切换", minWidth = 0.dp, minHeight = 0.dp, onClick = { op = if (op == "=") "|=" else "=" }) },
                    )
                    TextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(), label = "值：true / \"文本\" / 443 / .name / \"[前缀] \" + .name", singleLine = true)
                }
                DialogNote("生成的表达式：")
                Text(text = previewText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            } else {
                TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = "表达式原文（高级形态直接改这里）", singleLine = false)
            }
            DialogButtons(onDismiss = onDismiss, confirmLabel = "确定") {
                if (visual) {
                    if (preview == null || (path.trim().isNotEmpty() && !path.trim().startsWith("."))) showToast(previewText.removePrefix("⚠ "), long = true)
                    else onConfirm(preview)
                } else {
                    val v = text.trim()
                    if (v.isEmpty()) showToast("表达式不能为空（可用 × 删除该条）") else onConfirm(v)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 代理组 proxy-groups

private val GROUPS_PATH: YPath = listOf("proxy-groups")

private fun renameGroup(host: FormHost, index: Int, old: String, new: String): Boolean {
    val plan = RenameSync.group(host.doc, index, old, new)
    if (plan.blocked != null) { showToast(plan.blocked, long = true); return false }
    val ok = host.batch(plan.ops, "代理组 $old", "已改名", GROUPS_PATH + index + "name")
    if (ok && plan.synced > 0) showToast("已重命名，并同步更新 ${plan.synced} 处引用（其它代理组成员 / 规则目标）")
    return ok
}

@Composable
private fun GroupListPage(host: FormHost, nav: FormNav) {
    val doc = host.doc
    var picker by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }
    var plainDelete by remember { mutableStateOf(-1) }
    SeqListPage(
        host = host,
        seqPath = GROUPS_PATH,
        unit = "个代理组",
        emptyHint = "还没有代理组。新建后在详情页勾选成员节点 / 引用的集合。",
        headerActions = { TextButton(text = "新增代理组", onClick = { picker = true }) },
        rowTitle = { i, node -> proxyName(doc, node) ?: "代理组 #${i + 1}" },
        rowSummary = { i, node ->
            if (node.kind != YamlNode.Kind.MAP) "（别名 / 非映射项，只能在编辑器里改）"
            else {
                val type = FormValues.scalarText(doc, doc.findEntry(node, "type")?.node) ?: effectiveText(doc, GROUPS_PATH + i + "type").orEmpty()
                val members = FormValues.count(doc, GROUPS_PATH + i + "proxies")
                val use = FormValues.count(doc, GROUPS_PATH + i + "use")
                buildString {
                    append(typeLabel(GROUP_TYPES, type))
                    append(" · ").append(members).append(" 个成员")
                    if (use > 0) append(" · 引用 ").append(use).append(" 个集合")
                    if (FormValues.readBool(doc, GROUPS_PATH + i + "include-all") == true) append(" · include-all")
                }
            }
        },
        onOpen = { i, node ->
            if (node.kind == YamlNode.Kind.MAP) nav.push(FormRoute.Group(i))
            else showToast("这一项不是映射（别名或纯值），请在编辑器里直接改", long = true)
        },
        onDelete = { i, node ->
            val name = proxyName(doc, node)
            if (name != null) deleting = startDelete(doc, "proxy-groups", name) else plainDelete = i
        },
    )
    if (picker) {
        OptionPickerDialog(
            title = "新建代理组：选类型",
            summary = "按参考实现的模板写入（成员先放 DIRECT），再进详情页挑成员",
            options = GROUP_TYPES,
            onDismiss = { picker = false },
            onPick = { type ->
                picker = false
                val m = LinkedHashMap<String, Any?>(GROUP_TEMPLATE)
                val baseName = if (type == "select") GROUP_TEMPLATE["name"]?.toString() ?: "手动选择"
                else typeLabel(GROUP_TYPES, type).substringAfter(' ', type)
                m["name"] = uniqueName(baseName, host.seqNames(GROUPS_PATH))
                m["type"] = type
                if ((m["use"] as? List<*>)?.isEmpty() == true) m.remove("use")
                val index = seqSize(doc, GROUPS_PATH)
                if (host.insertItem(GROUPS_PATH, index, m, "代理组 ${m["name"]}")) nav.push(FormRoute.Group(index))
            },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = {}) }
    if (plainDelete >= 0) {
        val i = plainDelete
        ConfirmDialog(
            title = "删除代理组",
            text = "删除 `${pathText(GROUPS_PATH + i)}`（没有名字，无法检查引用）？这个操作只能靠编辑器撤销。",
            confirmLabel = "删除",
            onDismiss = { plainDelete = -1 },
            onConfirm = { plainDelete = -1; host.removeItem(GROUPS_PATH, i, "代理组 #${i + 1}") },
        )
    }
}

@Composable
private fun GroupDetailPage(host: FormHost, nav: FormNav, index: Int) {
    val doc = host.doc
    val base: YPath = GROUPS_PATH + index
    val node = doc.get(base)
    if (node == null || node.kind != YamlNode.Kind.MAP) {
        MissingItem("这个代理组已经不在了（或不是映射）")
        return
    }
    val name = FormValues.readRaw(doc, base + "name").orEmpty()
    val localType = FormValues.readRaw(doc, base + "type")?.ifBlank { null }
    val inheritedType = if (localType == null) effectiveText(doc, base + "type") else null
    val effType = localType ?: inheritedType ?: "select"
    val total = seqSize(doc, GROUPS_PATH)
    val title = name.ifEmpty { "代理组 #${index + 1}" }
    var renaming by remember { mutableStateOf(false) }
    var pickType by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }
    var plainDelete by remember { mutableStateOf(false) }
    val typeField = remember {
        FormField(path = "type", label = "类型", type = FormFieldType.SELECT, options = GROUP_TYPES, allowEmpty = true, emptyLabel = "默认（不覆写）")
    }

    SectionsPage(
        sections = GROUP_SECTIONS,
        base = base,
        host = host,
        onlyType = effType,
        footer = { AnchorSectionCard(host, "proxy-groups", base) },
        dynamicOptions = { field, path ->
            when (field.path) {
                "proxies" -> host.candidates("selectable-proxies", exclude = setOf(name))
                "use" -> host.candidates("providers")
                "default-selected" -> {
                    val out = ArrayList<FormOption>()
                    val selectable = host.candidates("selectable-proxies").mapTo(HashSet()) { it.value }
                    val seen = HashSet<String>()
                    for (m in FormValues.readList(doc, base + "proxies")) {
                        if (m in selectable && seen.add(m)) out.add(FormOption(m, "本组成员"))
                    }
                    out
                }
                else -> null
            }
        },
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailHeader(
                    title = title,
                    summary = "`${pathText(base)}` · 点此改名（会同步其它代理组成员与规则目标）",
                    onClick = { renaming = true },
                    endActions = {
                        MiniIconButton(MiuixIcons.Edit, "改名") { renaming = true }
                        Spacer(Modifier.width(8.dp))
                        MiniIconButton(MiuixIcons.Delete, "删除") {
                            if (name.isNotEmpty()) deleting = startDelete(doc, "proxy-groups", name) else plainDelete = true
                        }
                    },
                )
                RowCard(
                    title = "类型",
                    summary = when {
                        localType != null -> typeLabel(GROUP_TYPES, localType) +
                            (if (localType !in GROUP_TYPES.map { it.value }) "（未知类型：类型专属字段按 select 处理）" else "")
                        inheritedType != null -> "默认（不覆写）· 源码无本地 type 字段，生效值「$inheritedType」来自锚点继承"
                        else -> "默认（不覆写）· 源码无本地 type 字段；按 select 显示字段"
                    },
                    onClick = { pickType = true },
                    endActions = { TextButton(text = "选择", onClick = { pickType = true }) },
                )
            }
        },
    )
    if (renaming) {
        NameDialog(
            title = "代理组名",
            summary = "改名会同步其它代理组 proxies 里的引用和 rules / sub-rules 的尾部策略",
            initial = name,
            label = "代理组名（唯一）",
            onDismiss = { renaming = false },
            onConfirm = { new ->
                renaming = false
                if (new == name) return@NameDialog
                if (new in namesExcept(doc, GROUPS_PATH, index)) { showToast("已存在同名代理组", long = true); return@NameDialog }
                if (name.isEmpty()) host.set(base + "name", new, "代理组名") else renameGroup(host, index, name, new)
            },
        )
    }
    if (pickType) {
        SelectDialog(
            field = typeField,
            current = localType,
            candidates = emptyList(),
            onDismiss = { pickType = false },
            onPick = { v ->
                pickType = false
                if (v == null) { if (localType != null) host.clear(base + "type", "类型") else showToast("本来就没有本地 type 字段") }
                else host.set(base + "type", v, "类型")
            },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = { nav.pop() }) }
    if (plainDelete) {
        ConfirmDialog(
            title = "删除代理组",
            text = "删除 `${pathText(base)}`（没有名字，无法检查引用）？这个操作只能靠编辑器撤销。",
            confirmLabel = "删除",
            onDismiss = { plainDelete = false },
            onConfirm = { plainDelete = false; if (host.removeItem(GROUPS_PATH, index, title)) nav.pop() },
        )
    }
}

// ---------------------------------------------------------------- 路由规则 rules / 子规则里的规则列表

private class RuleEditTarget(val index: Int?, val insertAt: Int?)

@Composable
private fun RuleListPage(host: FormHost, seqPath: YPath, title: String) {
    val doc = host.doc
    var edit by remember { mutableStateOf<RuleEditTarget?>(null) }
    var textMode by remember { mutableStateOf(false) }
    val seq = doc.get(seqPath)
    val items: List<YamlNode> = if (seq != null && seq.kind == YamlNode.Kind.SEQ) seq.items else emptyList()

    fun ruleOf(node: YamlNode): String? =
        if (node.kind == YamlNode.Kind.MAP || node.kind == YamlNode.Kind.SEQ) null else FormValues.scalarText(doc, node)

    SeqListPage(
        host = host,
        seqPath = seqPath,
        unit = "条规则",
        // 序号只给顶层「路由规则」页：mihomo_box 的 `.rule-no` 只出现在那里；子规则在参考实现里
        // 是「子规则集合」maplist（键=名称、值=规则文本行），没有逐行序号，这里跟着不显示
        numbered = seqPath == TOP_RULES_PATH,
        // 匹配值过长（一个规则挂几十个域名 / 长参数）时只要一行 + 省略号，别把行撑成多行；
        // 所有规则页都套用（顶层路由规则 + 子规则），最长的那条决定行高太丑
        rowMaxLines = 1,
        emptyHint = "还没有规则。「添加规则」逐条填；「文本模式」一行一条整列表粘贴。",
        headerActions = {
            // 间距由 ListHeader 的 spacedBy(8.dp) 统一给
            TextButton(text = "文本模式", onClick = { textMode = true })
            TextButton(text = "＋ 添加规则", onClick = { edit = RuleEditTarget(index = null, insertAt = items.size) })
        },
        rowTitle = { i, node ->
            val raw = ruleOf(node)
            if (raw == null) "（第 ${i + 1} 项不是字符串）" else RuleText.title(RuleText.parse(raw))
        },
        rowSummary = { _, node ->
            val raw = ruleOf(node)
            if (raw == null) FormValues.scalarText(doc, node) ?: "只能在编辑器里改" else RuleText.summary(RuleText.parse(raw))
        },
        onOpen = { i, node ->
            if (ruleOf(node) == null) showToast("这一项不是规则字符串，请在编辑器里直接改", long = true)
            else edit = RuleEditTarget(index = i, insertAt = null)
        },
    )

    edit?.let { target ->
        val initialRaw = target.index?.let { i -> items.getOrNull(i)?.let(::ruleOf) }
        RuleEditDialog(
            initialRaw = initialRaw,
            host = host,
            onDismiss = { edit = null },
            onConfirm = { text ->
                edit = null
                if (target.index != null) host.setItem(seqPath, target.index, text, "规则 ${target.index + 1}")
                else host.insertItem(seqPath, target.insertAt ?: items.size, text, "规则")
            },
        )
    }
    if (textMode) {
        TextValueDialog(
            title = "$title · 文本模式",
            summary = "`${pathText(seqPath)}` · 一行一条，空行忽略；确定后整列表重写（列表里的注释会丢）",
            desc = null,
            current = items.mapNotNull(::ruleOf).joinToString("\n"),
            multiLine = true,
            onDismiss = { textMode = false },
            onConfirm = { v ->
                textMode = false
                val lines = v.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (items.any { ruleOf(it) == null }) showToast("列表里有非字符串项，文本模式不改它们；请在编辑器里处理", long = true)
                else host.set(seqPath, lines, title)
            },
        )
    }
}

/** 规则编辑（参考实现 editRuleSheet）：类型 / 匹配值 / 目标策略 / no-resolve，可切到原文编辑，两边互相同步。 */
@Composable
private fun RuleEditDialog(
    initialRaw: String?,
    host: FormHost,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initial = remember(initialRaw) { initialRaw?.let(RuleText::parse) }
    var type by remember { mutableStateOf(initial?.type ?: RULE_TYPES.first().value) }
    var value by remember { mutableStateOf(initial?.value.orEmpty()) }
    var policy by remember { mutableStateOf(initial?.policy ?: "DIRECT") }
    var noResolve by remember { mutableStateOf(initial?.noResolve == true) }
    var keepFlags by remember(initialRaw) { mutableStateOf(initial?.flags?.filter { !it.equals("no-resolve", ignoreCase = true) } ?: emptyList()) }
    var rawMode by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(initialRaw.orEmpty()) }
    var pickType by remember { mutableStateOf(false) }
    var pickPolicy by remember { mutableStateOf(false) }
    val spec = RuleText.spec(type)
    val isMatch = type == "MATCH"
    val noValue = isMatch || spec?.noPayload == true
    val subRule = spec?.targetKind == "sub-rules"
    val policyCandidates = if (subRule) host.candidates("sub-rules")
    else host.candidates("policies")
    val valueCandidates = if (spec?.targetKind == "rule-providers") host.candidates("rule-providers") else emptyList()
    val preview = RuleText.format(type, value.trim(), policy.trim(), noResolve && !isMatch, keepFlags)

    fun syncVisFromText(): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        val p = RuleText.parse(t)
        type = p.type; value = p.value; policy = p.policy; noResolve = p.noResolve
        keepFlags = p.flags.filter { !it.equals("no-resolve", ignoreCase = true) }
        return true
    }

    WindowDialog(
        show = true,
        title = if (initialRaw == null) "添加规则" else "编辑规则",
        summary = spec?.label ?: "未知类型 $type（照样按 类型,匹配值,策略 写）",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    text = if (rawMode) "切换为可视化编辑" else "切换为文本编辑",
                    minWidth = 0.dp, minHeight = 0.dp,
                    onClick = {
                        if (rawMode) { syncVisFromText(); rawMode = false } else { raw = preview; rawMode = true }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (rawMode) {
                TextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "规则原文：类型,匹配值,策略[,no-resolve]",
                    singleLine = false,
                )
                DialogNote("切回可视化时按逗号拆回四件套；MATCH 没有匹配值")
            } else {
                BasicComponent(
                    title = "类型",
                    summary = spec?.let { "${it.value} · ${it.label}" } ?: type,
                    endActions = { Text(text = if (pickType) "收起" else "选择", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) },
                    onClick = { pickType = !pickType },
                )
                if (pickType) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (t in RULE_TYPES) {
                            BasicComponent(title = t.value, summary = t.label, onClick = { type = t.value; pickType = false })
                        }
                    }
                }
                if (!noValue) {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        label = spec?.placeholder?.let { "匹配值，例：$it" } ?: "匹配值",
                        singleLine = spec?.multi != true,
                    )
                    if (valueCandidates.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            for (c in valueCandidates) BasicComponent(title = c.value, summary = c.label, onClick = { value = c.value })
                        }
                    }
                }
                BasicComponent(
                    title = if (subRule) "目标子规则" else "目标策略",
                    summary = policy.ifEmpty { "（未选）" },
                    endActions = { Text(text = if (pickPolicy) "收起" else "选择", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) },
                    onClick = { pickPolicy = !pickPolicy },
                )
                if (pickPolicy) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (policyCandidates.isEmpty()) DialogNote("文档里还没有可选的目标")
                        for (c in policyCandidates) {
                            BasicComponent(title = c.value, summary = c.label.takeIf { it != c.value }, onClick = { policy = c.value; pickPolicy = false })
                        }
                    }
                }
                if (!isMatch) {
                    BasicComponent(
                        title = "no-resolve",
                        summary = "不为匹配解析域名（IP 类规则常用）" + (if (spec?.ipRule == true) " · 本类型是 IP 规则" else ""),
                        endActions = { Switch(checked = noResolve, onCheckedChange = { noResolve = it }) },
                        onClick = { noResolve = !noResolve },
                    )
                }
                if (keepFlags.isNotEmpty()) DialogNote("原规则带有参数 ${keepFlags.joinToString(",")}，会原样保留在末尾")
                DialogNote("预览：$preview")
            }
            DialogButtons(onDismiss = onDismiss, confirmLabel = "确定") {
                if (rawMode) {
                    val t = raw.trim()
                    if (t.isEmpty()) showToast("规则不能为空") else onConfirm(t)
                } else {
                    when {
                        !noValue && value.trim().isEmpty() -> showToast("请填写匹配值")
                        policy.trim().isEmpty() -> showToast(if (subRule) "请选择目标子规则" else "请选择目标策略")
                        else -> onConfirm(preview)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 规则集合 rule-providers

private val RULE_PROVIDERS_PATH: YPath = listOf("rule-providers")

private fun ruleProviderExt(format: String?): String = when (format) {
    "text" -> "txt"
    "mrs" -> "mrs"
    else -> "yaml"
}

/** 新建规则集合（参考实现 addEpSheet）：RULE_PROVIDER_DEFAULT + 类型 / 行为 / 格式 / 链接。 */
private fun ruleProviderTemplate(name: String, type: String, behavior: String, format: String, url: String): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>(RULE_PROVIDER_DEFAULT)
    m["type"] = type
    m["behavior"] = behavior
    m["format"] = format
    m["url"] = url
    when (type) {
        "file" -> {
            m["path"] = "./rules/${safeFileStem(name, "ruleset")}.${ruleProviderExt(format)}"
            for (k in FILE_HIDDEN_KEYS["ep"].orEmpty()) m.remove(k)
        }
        "inline" -> {
            m.remove("url"); m.remove("interval"); m.remove("format")
            m["payload"] = emptyList<Any?>()
        }
    }
    if (m["url"] == "") m.remove("url")
    return m
}

private fun renameRuleProvider(host: FormHost, old: String, new: String): Boolean {
    val plan = RenameSync.ruleProvider(host.doc, old, new)
    if (plan.blocked != null) { showToast(plan.blocked, long = true); return false }
    val ok = host.batch(plan.ops, "规则集合 $old", "已改名", RULE_PROVIDERS_PATH + new)
    if (ok && plan.synced > 0) showToast("已重命名，并同步更新 ${plan.synced} 条 RULE-SET 规则")
    return ok
}

@Composable
private fun RuleProviderListPage(host: FormHost, nav: FormNav) {
    val doc = host.doc
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }
    MapListPage(
        host = host,
        mapPath = RULE_PROVIDERS_PATH,
        unit = "个规则集",
        emptyHint = "还没有规则集合。新建后在路由规则里用 RULE-SET,<名字>,<策略> 引用。",
        headerActions = { TextButton(text = "新增规则集", onClick = { creating = true }) },
        rowSummary = { name, node ->
            if (node == null || node.kind != YamlNode.Kind.MAP) "（不是映射，只能在编辑器里改）"
            else {
                // 本地行优先；纯经 <<: *锚点 继承来的参数走展开视图，列表摘要不丢配置参数
                val type = FormValues.scalarText(doc, doc.findEntry(node, "type")?.node)
                    ?: FormValues.effectiveText(doc, RULE_PROVIDERS_PATH + name + "type")
                    ?: ""
                val behavior = FormValues.scalarText(doc, doc.findEntry(node, "behavior")?.node)
                    ?: FormValues.effectiveText(doc, RULE_PROVIDERS_PATH + name + "behavior")
                val format = FormValues.scalarText(doc, doc.findEntry(node, "format")?.node)
                    ?: FormValues.effectiveText(doc, RULE_PROVIDERS_PATH + name + "format")
                val src = FormValues.scalarText(doc, doc.findEntry(node, "url")?.node)
                    ?: FormValues.scalarText(doc, doc.findEntry(node, "path")?.node)
                    ?: FormValues.effectiveText(doc, RULE_PROVIDERS_PATH + name + "url")
                    ?: FormValues.effectiveText(doc, RULE_PROVIDERS_PATH + name + "path")
                buildString {
                    append(typeLabel(RULE_PROVIDER_TYPES, type))
                    behavior?.let { append(" · ").append(it) }
                    format?.let { append("/").append(it) }
                    if (type == "inline") append(" · ").append(FormValues.count(doc, RULE_PROVIDERS_PATH + name + "payload")).append(" 条")
                    else if (!src.isNullOrEmpty()) append(" · ").append(src.take(60))
                }
            }
        },
        onOpen = { name -> nav.push(FormRoute.RuleProvider(name)) },
        // ✎ = 编辑所有配置（详情页有全部字段与「改名」，与 mihomo_box 面板一致）
        onDelete = { name -> deleting = startDelete(doc, "rule-providers", name) },
    )
    if (creating) {
        var behavior by remember { mutableStateOf("domain") }
        var format by remember { mutableStateOf("yaml") }
        NewProviderDialog(
            title = "新建规则集合",
            nameLabel = "规则集名（RULE-SET 规则里引用它）",
            types = RULE_PROVIDER_TYPES,
            existing = host.mapKeys(RULE_PROVIDERS_PATH),
            urlLabel = "规则集链接 url",
            onDismiss = { creating = false },
            extra = {
                BasicComponent(
                    title = "规则类型 behavior",
                    summary = behavior,
                    endActions = {
                        TextButton(text = "切换", minWidth = 0.dp, minHeight = 0.dp, onClick = {
                            behavior = when (behavior) { "domain" -> "ipcidr"; "ipcidr" -> "classical"; else -> "domain" }
                        })
                    },
                )
                BasicComponent(
                    title = "文件格式 format",
                    summary = format,
                    endActions = {
                        TextButton(text = "切换", minWidth = 0.dp, minHeight = 0.dp, onClick = {
                            format = when (format) { "yaml" -> "text"; "text" -> "mrs"; else -> "yaml" }
                        })
                    },
                )
            },
            onConfirm = { name, type, url ->
                creating = false
                if (host.set(RULE_PROVIDERS_PATH + name, ruleProviderTemplate(name, type, behavior, format, url), "规则集合 $name")) nav.push(FormRoute.RuleProvider(name))
            },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = {}) }
}

@Composable
private fun RuleProviderDetailPage(host: FormHost, nav: FormNav, name: String) {
    val doc = host.doc
    val base: YPath = RULE_PROVIDERS_PATH + name
    val node = doc.get(base)
    if (node == null || node.kind != YamlNode.Kind.MAP) {
        MissingItem("这个规则集合已经不在了（或不是映射）")
        return
    }
    val type = FormValues.readRaw(doc, base + "type")?.ifBlank { null } ?: effectiveText(doc, base + "type") ?: "http"
    val format = FormValues.readRaw(doc, base + "format")?.ifBlank { null } ?: effectiveText(doc, base + "format") ?: "yaml"
    var renaming by remember { mutableStateOf(false) }
    var payloadEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeleteFlow?>(null) }

    val intercept: FieldInterceptor = { field, path, value ->
        if (field.path == "type" && value is String) {
            val ops = ArrayList<BatchOp>()
            ops.add(BatchOp.Set(path, value))
            if (value == "file") {
                for (k in FILE_HIDDEN_KEYS["ep"].orEmpty()) ops.add(BatchOp.Remove(base + k))
                if (!FormValues.hasEffective(doc, base + "path")) {
                    // format / path 都可能经 `<<: *锚点` 继承：按展开视图判断缺失，扩展名用生效的 format
                    ops.add(BatchOp.Set(base + "path", "./rules/${safeFileStem(name, "ruleset")}.${ruleProviderExt(format)}"))
                }
            }
            if (value == "inline" && doc.get(base + "payload") == null) ops.add(BatchOp.Set(base + "payload", emptyList<Any?>()))
            host.batch(ops, field.label, reveal = path)
            true
        } else false
    }

    SectionsPage(
        sections = RULE_PROVIDER_SECTIONS,
        base = base,
        host = host,
        onlyType = type,
        intercept = intercept,
        footer = {
            AnchorSectionCard(host, "rule-providers", base)
        },
        header = {
            DetailHeader(
                title = name,
                summary = "`${pathText(base)}` · ${typeLabel(RULE_PROVIDER_TYPES, type)} · 点此改名（会同步 RULE-SET 规则）",
                onClick = { renaming = true },
                endActions = {
                    MiniIconButton(MiuixIcons.Edit, "改名") { renaming = true }
                    Spacer(Modifier.width(8.dp))
                    MiniIconButton(MiuixIcons.Delete, "删除") { deleting = startDelete(doc, "rule-providers", name) }
                },
            )
        },
        customRow = { row ->
            when (row.kind) {
                "rule-provider-payload" -> if (type == "inline") {
                    val n = FormValues.count(doc, base + "payload")
                    RowCard(
                        title = "内联规则 payload",
                        summary = "$n 条 · 一行一条（按 behavior 写域名 / CIDR / 经典规则），不带目标策略",
                        onClick = { payloadEditor = true },
                        endActions = { MiniIconButton(MiuixIcons.Edit, "编辑") { payloadEditor = true } },
                    )
                }
                "rule-provider-file-ops" -> if (type == "file") {
                    ProviderFileOpsRow(
                        host = host,
                        base = base,
                        kind = "ep",
                        defaultPath = "./rules/${safeFileStem(name, "ruleset")}.${ruleProviderExt(format)}",
                        newFileText = "# 新建规则集文件（内容取决于 behavior）\n# domain：每行一个域名/后缀\n#   .google.com\n# ipcidr：每行一个 CIDR\n#   91.108.56.0/22\n# classical：YAML payload 规则列表\npayload:\n  # - DOMAIN-SUFFIX,example.com\n",
                    )
                }
            }
        },
    )
    if (renaming) {
        NameDialog(
            title = "改名",
            summary = "`${pathText(base)}` · 会同步 rules 里的 RULE-SET,名字",
            initial = name,
            label = "新名字",
            onDismiss = { renaming = false },
            onConfirm = { new ->
                renaming = false
                if (new != name && renameRuleProvider(host, name, new)) nav.replaceTop(FormRoute.RuleProvider(new))
            },
        )
    }
    if (payloadEditor) {
        val payloadNode = doc.get(base + "payload")
        val okList = payloadNode == null || doc.isNullText(payloadNode) || payloadNode.kind == YamlNode.Kind.SEQ
        TextValueDialog(
            title = "内联规则 payload",
            summary = "`${pathText(base + "payload")}` · 一行一条，空行忽略；确定后整列表重写",
            desc = null,
            current = FormValues.readList(doc, base + "payload").joinToString("\n"),
            multiLine = true,
            onDismiss = { payloadEditor = false },
            onConfirm = { v ->
                payloadEditor = false
                val lines = v.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (!okList) showToast("payload 不是列表，请在编辑器里处理", long = true)
                else host.set(base + "payload", lines, "payload")
            },
        )
    }
    deleting?.let { flow -> DeleteFlowDialogs(flow, host, onDismiss = { deleting = null }, onDeleted = { nav.pop() }) }
}

// ---------------------------------------------------------------- 子规则 sub-rules

private val SUB_RULES_PATH: YPath = listOf("sub-rules")

/** 顶层路由规则列表的路径：`.rule-no` 序号只在这里显示。 */
private val TOP_RULES_PATH: YPath = listOf("rules")

@Composable
private fun SubRuleListPage(host: FormHost, nav: FormNav) {
    val doc = host.doc
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    MapListPage(
        host = host,
        mapPath = SUB_RULES_PATH,
        unit = "组子规则",
        emptyHint = "还没有子规则。子规则是一组命名的规则列表，由路由规则里的 SUB-RULE,(条件),<名字> 跳转进来。",
        headerActions = { TextButton(text = "新增子规则", onClick = { creating = true }) },
        rowSummary = { name, node ->
            if (node == null) "空"
            else if (node.kind != YamlNode.Kind.SEQ && !doc.isNullText(node)) "（不是列表，只能在编辑器里改）"
            else "${FormValues.count(doc, SUB_RULES_PATH + name)} 条规则"
        },
        onOpen = { name -> nav.push(FormRoute.Rules(SUB_RULES_PATH + name, "子规则 $name")) },
        renameNote = "引用它的 SUB-RULE 规则不会跟着改",
        onRename = { old, new -> host.rename(SUB_RULES_PATH + old, new, old) },
        onDelete = { name -> deleting = name },
    )
    if (creating) {
        NameDialog(
            title = "新建子规则",
            summary = "先建一个空列表，再在里面加规则",
            initial = "",
            label = "子规则名",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                if (name in host.mapKeys(SUB_RULES_PATH)) showToast("已经有叫「$name」的了，换个名字", long = true)
                else if (host.set(SUB_RULES_PATH + name, emptyList<Any?>(), "子规则 $name")) nav.push(FormRoute.Rules(SUB_RULES_PATH + name, "子规则 $name"))
            },
        )
    }
    deleting?.let { name ->
        ConfirmDialog(
            title = "删除子规则",
            text = "删除子规则「$name」？引用它的 SUB-RULE 规则不会自动清理。",
            confirmLabel = "删除",
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; host.clear(SUB_RULES_PATH + name, "子规则 $name") },
        )
    }
}

// ---------------------------------------------------------------- 流量隧道 tunnels

private val TUNNELS_PATH: YPath = listOf("tunnels")

/** 新建隧道（参考实现 tunnels 页）：单行写法直接写字符串；映射写法要求监听地址与目标都填。 */
@Composable
private fun NewTunnelDialog(host: FormHost, onDismiss: () -> Unit, onConfirm: (Any) -> Unit) {
    var asMap by remember { mutableStateOf(true) }
    var line by remember { mutableStateOf("") }
    var tcp by remember { mutableStateOf(true) }
    var udp by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var proxy by remember { mutableStateOf("") }
    var pickProxy by remember { mutableStateOf(false) }
    val proxies = host.candidates("selectable-proxies")
    WindowDialog(
        show = true,
        title = "新增隧道",
        summary = "把本地端口的 tcp/udp 流量转发到目标地址，可指定经由的策略",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = if (asMap) "改用单行写法" else "改用映射写法", minWidth = 0.dp, minHeight = 0.dp, onClick = { asMap = !asMap })
            }
            if (asMap) {
                BasicComponent(title = "tcp", endActions = { Switch(checked = tcp, onCheckedChange = { tcp = it }) }, onClick = { tcp = !tcp })
                BasicComponent(title = "udp", endActions = { Switch(checked = udp, onCheckedChange = { udp = it }) }, onClick = { udp = !udp })
                TextField(value = address, onValueChange = { address = it }, modifier = Modifier.fillMaxWidth(), label = "监听地址 address，如 127.0.0.1:6553", singleLine = true)
                TextField(value = target, onValueChange = { target = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), label = "目标地址 target，如 8.8.8.8:53", singleLine = true)
                BasicComponent(
                    title = "经由代理 proxy",
                    summary = proxy.ifEmpty { "留空直连" },
                    endActions = { Text(text = if (pickProxy) "收起" else "选择", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) },
                    onClick = { pickProxy = !pickProxy },
                )
                if (pickProxy) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        BasicComponent(title = "留空直连", onClick = { proxy = ""; pickProxy = false })
                        for (c in proxies) BasicComponent(title = c.value, summary = c.label.takeIf { it != c.value }, onClick = { proxy = c.value; pickProxy = false })
                    }
                }
            } else {
                TextField(value = line, onValueChange = { line = it }, modifier = Modifier.fillMaxWidth(), label = "协议,监听地址,目标地址[,代理]", singleLine = true)
                DialogNote("例：tcp/udp,127.0.0.1:6553,8.8.8.8:53 或 tcp,127.0.0.1:6666,rds.mysql.com:3306,Proxy")
            }
            DialogButtons(onDismiss = onDismiss, confirmLabel = "创建") {
                if (asMap) {
                    val nets = buildList { if (tcp) add("tcp"); if (udp) add("udp") }
                    when {
                        nets.isEmpty() -> showToast("至少勾选一种协议")
                        address.trim().isEmpty() || target.trim().isEmpty() -> showToast("监听地址和目标地址都要填")
                        else -> onConfirm(TunnelText.toMap(TunnelText.Parts(nets, address.trim(), target.trim(), proxy.trim().ifEmpty { null })))
                    }
                } else {
                    val err = TunnelText.validateLine(line.trim())
                    if (err != null) showToast(err, long = true) else onConfirm(line.trim())
                }
            }
        }
    }
}

@Composable
private fun TunnelListPage(host: FormHost, nav: FormNav) {
    val doc = host.doc
    var creating by remember { mutableStateOf(false) }
    SeqListPage(
        host = host,
        seqPath = TUNNELS_PATH,
        unit = "条隧道",
        emptyHint = "还没有隧道。隧道把本地端口的 tcp/udp 流量转发到目标地址，可指定经由的策略。",
        headerActions = { TextButton(text = "新增隧道", onClick = { creating = true }) },
        rowTitle = { i, node ->
            if (node.kind == YamlNode.Kind.MAP) FormValues.scalarText(doc, doc.findEntry(node, "address")?.node)?.ifBlank { null } ?: "隧道 #${i + 1}"
            else FormValues.scalarText(doc, node)?.let { TunnelText.parse(it)?.address } ?: "隧道 #${i + 1}"
        },
        rowSummary = { i, node ->
            if (node.kind == YamlNode.Kind.MAP) {
                val nets = FormValues.readListField(doc, FormField("network", "network", FormFieldType.LIST), TUNNELS_PATH + i + "network").joinToString("/")
                val target = FormValues.scalarText(doc, doc.findEntry(node, "target")?.node).orEmpty()
                val proxy = FormValues.scalarText(doc, doc.findEntry(node, "proxy")?.node)
                "$nets → $target" + (if (!proxy.isNullOrEmpty()) " · 经 $proxy" else " · 直连")
            } else {
                val raw = FormValues.scalarText(doc, node)
                raw?.let { TunnelText.parse(it)?.let(TunnelText::summary) ?: "（无法解析的字符串写法）$it" } ?: "（非字符串项）"
            }
        },
        onOpen = { i, _ -> nav.push(FormRoute.Tunnel(i)) },
    )
    if (creating) {
        NewTunnelDialog(
            host = host,
            onDismiss = { creating = false },
            onConfirm = { v ->
                creating = false
                val index = seqSize(doc, TUNNELS_PATH)
                if (host.insertItem(TUNNELS_PATH, index, v, "隧道")) nav.push(FormRoute.Tunnel(index))
            },
        )
    }
}

@Composable
private fun TunnelDetailPage(host: FormHost, nav: FormNav, index: Int) {
    val doc = host.doc
    val base: YPath = TUNNELS_PATH + index
    val node = doc.get(base)
    if (node == null) {
        MissingItem("这条隧道已经不在了")
        return
    }
    var deleting by remember { mutableStateOf(false) }
    if (deleting) {
        ConfirmDialog(
            title = "删除隧道",
            text = "删除 `${pathText(base)}`？这个操作只能靠编辑器撤销。",
            confirmLabel = "删除",
            onDismiss = { deleting = false },
            onConfirm = { deleting = false; if (host.removeItem(TUNNELS_PATH, index, "隧道")) nav.pop() },
        )
    }
    if (node.kind == YamlNode.Kind.MAP) {
        SectionsPage(
            sections = TUNNEL_SECTIONS,
            base = base,
            host = host,
            intercept = { field, _, value ->
                // 监听地址 / 目标地址是必填：空值不写（否则内核起不来）
                if ((field.path == "address" || field.path == "target") && (value == null || value == "")) {
                    showToast("${field.label} 是必填项，不能留空", long = true); true
                } else if (field.path == "network" && value is List<*> && value.isEmpty()) {
                    showToast("至少保留一种协议（tcp / udp）", long = true); true
                } else false
            },
            header = {
                DetailHeader(
                    title = FormValues.readRaw(doc, base + "address")?.ifBlank { null } ?: "隧道 #${index + 1}",
                    summary = "`${pathText(base)}` · 映射写法（network 按内核要求写成列表）",
                    endActions = { MiniIconButton(MiuixIcons.Delete, "删除") { deleting = true } },
                )
            },
        )
        return
    }
    // 字符串写法：只读展示 + 转映射 / 直接改字符串
    val raw = FormValues.scalarText(doc, node).orEmpty()
    val parts = TunnelText.parse(raw)
    var editing by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            DetailHeader(
                title = parts?.address ?: "隧道 #${index + 1}",
                summary = "`${pathText(base)}` · 单行写法：`协议,监听地址,目标地址[,代理]`",
                endActions = { MiniIconButton(MiuixIcons.Edit, "编辑") { editing = true } },
            )
        }
        item {
            RowCard(
                title = "原文",
                summary = raw.ifEmpty { "（空）" } + (if (parts == null) " · 内核认不出这个格式（要 3 或 4 段）" else ""),
                onClick = { editing = true },
                endActions = { MiniIconButton(MiuixIcons.Edit, "编辑") { editing = true } },
            )
        }
        if (parts != null) {
            item {
                RowCard(
                    title = "转成映射写法",
                    summary = "改写为 network / address / target / proxy 四个键，之后就能逐项编辑；语义不变",
                    onClick = { host.setItem(TUNNELS_PATH, index, TunnelText.toMap(parts), "隧道 ${parts.address}") },
                    endActions = { Text(text = "转换", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) },
                )
            }
        }
    }
    if (editing) {
        TextValueDialog(
            title = "隧道单行写法",
            summary = "`${pathText(base)}` · 例：tcp/udp,127.0.0.1:6553,114.114.114.114:53,Proxy",
            desc = null,
            current = raw,
            multiLine = false,
            onDismiss = { editing = false },
            onConfirm = { v ->
                editing = false
                val err = TunnelText.validateLine(v)
                if (err != null) showToast(err, long = true) else host.setItem(TUNNELS_PATH, index, v, "隧道")
            },
        )
    }
}
