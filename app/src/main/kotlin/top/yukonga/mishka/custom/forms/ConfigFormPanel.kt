package top.yukonga.mishka.custom.forms

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.TetherInterfaceEditDialog
import top.yukonga.mishka.ui.component.tetherInterfaceSummary
import top.yukonga.mishka.ui.util.sheetContentSafePadding
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.scripta.editor.CodeEditorController
import top.yukonga.scripta.editor.text.TextPosition

/**
 * 配置表单面板（自定义功能，见 mishka-custom/FORMS.md）：mihomo_box「配置页」的 Android 版。
 *
 * 与锚点面板同一套约定：
 *  1. **只改被编辑的那个键 / 那一项**：写回走 [YamlPatch] 的三层策略（手术式 → 块级 → 拒绝），
 *     其它顶层块逐字节不动，锚点/别名/注释/CRLF 全部保留（由 tools/forms 的性质测试 + 双引擎对拍保证）。
 *  2. **改动先落在编辑器草稿里**：经 [CodeEditorController.replaceRange] 整篇替换，形成一个撤销单元；
 *     真正写盘仍走编辑器顶栏的保存（内核校验 + 失败回滚）。
 *  3. **不做的事**：路径落在纯值下面的字段一律拒绝（[YamlDoc.canSet]），宁可不动也不写坏配置。
 *
 * P1：hub 13 格 + 全局配置 / DNS / 域名嗅探 / 入站 / NTP / 实验性 的字段页。
 * P2（本文件 + [FlowFormPages.kt]）：出站代理 / 代理集合 / 代理组 / 路由规则 / 规则集合 / 子规则 / 流量隧道
 * 七个「列表 → 详情」页，列表项的增删挪改走序列级补丁（[YamlPatch.insertItem] 等）。
 * P3（[P3FormEditors.kt]）：DNS 服务器构建器、Android 应用多选、规则集选择器、fake-ip 规则、headers 与 DNS/hosts 映射编辑器。
 */
@Composable
fun MishkaConfigFormPanel(
    visible: Boolean,
    controller: CodeEditorController,
    fileName: String,
    onClose: () -> Unit,
    fileBaseDir: String? = null,
) {
    val version = if (visible) controller.documentVersion else -1
    val text = remember(version) { if (visible) controller.getText() else "" }
    val doc = remember(version, text) { YamlDoc.parse(text) }
    // 导航栈只在面板重新打开时重置：每次写回都会换一份 doc，页面位置得留住
    val nav = remember(visible) { FormNav() }

    fun apply(newText: String, toast: String, revealLine1: Int?) {
        if (newText == text) {
            showToast(toast)
            return
        }
        // 终点必须取编辑器里旧文本的末位置：replaceRange 按旧文档钳制两端，取新文本的末位置时，
        // 新文本末行更短就会在旧文本尾部残留字符（挪动末尾规则时 `MATCH,REJECT` 残留的 `CT` 接到了上一条后面）
        val oldLines = controller.getText().split('\n')
        controller.replaceRange(
            TextPosition(0, 0),
            TextPosition(oldLines.lastIndex, oldLines.last().length),
            newText,
        )
        if (revealLine1 != null) controller.jumpToLine((revealLine1 - 1).coerceAtLeast(0))
        showToast(toast)
    }

    val host = remember(doc, fileBaseDir) { FormHost(doc, ::apply, fileBaseDir) }
    val route = nav.current

    WindowBottomSheet(
        show = visible,
        title = route?.let { routeTitle(it, doc) } ?: "配置表单",
        onDismissRequest = onClose,
        startAction = if (route == null) null else {
            { IconButton(onClick = { nav.pop() }) { Icon(MiuixIcons.Back, "返回", tint = MiuixTheme.colorScheme.onBackground) } }
        },
        endAction = {
            val dismiss = LocalDismissState.current
            IconButton(onClick = { dismiss?.invoke() }) {
                Icon(MiuixIcons.Close, "关闭", tint = MiuixTheme.colorScheme.onBackground)
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .sheetContentSafePadding(),
        ) {
            when (route) {
                null -> FormHub(doc = doc, fileName = fileName, onOpen = { nav.push(it) })
                is FormRoute.Section -> if (route.key == "inbound") InboundPage(host = host, nav = nav)
                    else SectionsPage(sections = sectionsOf(route.key), base = emptyList(), host = host)
                is FormRoute.Listener -> ListenerDetailsPage(route.index, host, nav)
                else -> FlowFormPage(route = route, host = host, nav = nav)
            }
        }
    }
}

// ---------------------------------------------------------------- 路由 / 导航

/** 面板里的页面。P1 的六个分区页是 [Section]；其余都是 P2 的「列表 → 详情」。 */
internal sealed class FormRoute {
    class Section(val key: String) : FormRoute()
    /** 节点列表：`proxies` 或某个 inline 代理集合的 `payload`。 */
    class Proxies(val seqPath: YPath, val title: String) : FormRoute()
    class Proxy(val seqPath: YPath, val index: Int) : FormRoute()
    object Providers : FormRoute()
    class Provider(val name: String) : FormRoute()
    object Groups : FormRoute()
    class Group(val index: Int) : FormRoute()
    /** 规则列表：`rules` 或 `sub-rules.<name>`。 */
    class Rules(val seqPath: YPath, val title: String) : FormRoute()
    object RuleProviders : FormRoute()
    class RuleProvider(val name: String) : FormRoute()
    object SubRules : FormRoute()
    object Tunnels : FormRoute()
    class Tunnel(val index: Int) : FormRoute()
    class Listener(val index: Int) : FormRoute()
}

internal class FormNav {
    val stack = mutableStateListOf<FormRoute>()
    val current: FormRoute? get() = stack.lastOrNull()
    fun push(route: FormRoute) { stack.add(route) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun replaceTop(route: FormRoute) { pop(); push(route) }
}

private fun routeTitle(route: FormRoute, doc: YamlDoc): String = when (route) {
    is FormRoute.Section -> pageTitle(route.key)
    is FormRoute.Proxies -> route.title
    is FormRoute.Proxy -> FormValues.readRaw(doc, route.seqPath + route.index + "name")?.ifBlank { null } ?: "节点 #${route.index + 1}"
    FormRoute.Providers -> "代理集合"
    is FormRoute.Provider -> route.name
    FormRoute.Groups -> "代理组"
    is FormRoute.Group -> FormValues.readRaw(doc, listOf("proxy-groups", route.index, "name"))?.ifBlank { null } ?: "代理组 #${route.index + 1}"
    is FormRoute.Rules -> route.title
    FormRoute.RuleProviders -> "规则集合"
    is FormRoute.RuleProvider -> route.name
    FormRoute.SubRules -> "子规则"
    FormRoute.Tunnels -> "流量隧道"
    is FormRoute.Tunnel -> "隧道 #${route.index + 1}"
    is FormRoute.Listener -> FormValues.readRaw(doc, listOf("listeners", route.index, "name"))?.ifBlank { null } ?: "监听器 #${route.index + 1}"
}

// ---------------------------------------------------------------- 写回

private val BUILTIN_POLICY_NAMES: Set<String> =
    (BUILTIN_POLICIES.map { it.value } + "GLOBAL").map { it.uppercase() }.toSet()
private val NON_SELECTABLE_PROXY_TYPES: Set<String> =
    setOf("direct", "reject", "reject-drop", "pass", "pass-rule", "compatible", "dns", "rematch")

/**
 * 一次表单会话里的文档 + 写回。所有写操作都从这里过：统一做可写性检查、「没改动」提示、写后定位。
 * 每个方法返回是否真的改了文本（页面据此决定要不要跳转 / 返回）。
 */
internal class FormHost(
    val doc: YamlDoc,
    private val applyText: (newText: String, toast: String, revealLine1: Int?) -> Unit,
    /** 当前编辑文件所属订阅的 imported/ 目录；file 类型合集的源文件上传 / 编辑靠它落盘。null = 无文件能力。 */
    val fileBaseDir: String? = null,
) {
    /** 行级手术（锚点继承等）的落地通道：整篇替换，与 set / batch 同一套「无变化」提示。 */
    fun applyRawText(newText: String, label: String, revealLine1: Int? = null): Boolean {
        if (newText == doc.dump()) {
            showToast("$label 没有变化")
            return false
        }
        applyText(newText, "已写入 $label", revealLine1)
        return true
    }

    private fun refuse(label: String) {
        // 引擎拒绝（路径下面是纯值 / 别名、flow 里装不下的集合或多行文本…）：宁可不动
        showToast("$label 未改动：这个位置不能这样写，请在编辑器里直接改这一段", long = true)
    }

    private fun commit(out: YamlDoc, label: String, verb: String, reveal: YPath?): Boolean {
        if (out === doc) {
            refuse(label)
            return false
        }
        val dumped = out.dump()
        if (dumped == doc.dump()) {
            showToast("$label 没有变化")
            return false
        }
        applyText(dumped, "$verb $label", reveal?.let { lineOfPath(out, it) })
        return true
    }

    fun set(path: YPath, value: Any?, label: String): Boolean {
        if (!doc.canSet(path)) {
            showToast("$label 不在可编辑位置（该路径下面是纯值 / 别名），请在编辑器里直接改", long = true)
            return false
        }
        // 继承感知：值来自 `<<:` 继承时按参考实现写穿锚点 / 物化整块，绝不建半块本地映射顶掉继承内容
        val w = AnchorInheritance.applySetAware(doc, path, value)
        val named = if (w.anchor != null) "$label（已同步到锚点 &${w.anchor}，继承它的所有条目一起生效）" else label
        return commit(w.doc, named, "已写入", w.path)
    }

    fun clear(path: YPath, label: String): Boolean {
        if (doc.get(path) == null) {
            if (FormValues.hasEffective(doc, path)) {
                showToast("$label 来自锚点继承，本地没有可删的行：直接填入新值即可覆盖，或在「锚点与继承」里解除继承", long = true)
            } else showToast("$label 本来就没有设置")
            return false
        }
        return commit(YamlPatch.removeKey(doc, path), label, "已清空", null)
    }

    fun insertItem(seqPath: YPath, index: Int, value: Any?, label: String): Boolean =
        commit(YamlPatch.insertItem(doc, seqPath, index, value), label, "已新增", seqPath + index)

    fun setItem(seqPath: YPath, index: Int, value: Any?, label: String): Boolean =
        commit(YamlPatch.setItem(doc, seqPath, index, value), label, "已写入", seqPath + index)

    fun removeItem(seqPath: YPath, index: Int, label: String): Boolean =
        commit(YamlPatch.removeItem(doc, seqPath, index), label, "已删除", null)

    fun moveItem(seqPath: YPath, from: Int, to: Int, label: String): Boolean =
        commit(YamlPatch.moveItem(doc, seqPath, from, to), label, "已移动", seqPath + to)

    fun rename(path: YPath, newKey: String, label: String): Boolean {
        val parent = doc.get(path.dropLast(1))
        if (parent != null && doc.findEntry(parent, newKey) != null) {
            showToast("已经有叫「$newKey」的了，换个名字", long = true)
            return false
        }
        return commit(YamlPatch.renameKey(doc, path, newKey), label, "已改名", path.dropLast(1) + newKey)
    }

    /**
     * 一次写多处（改名同步引用 / 切换类型时的连带清理 / 「其他参数」整块替换…）：按顺序应用，
     * 任一步被引擎拒绝就整批放弃、文档不动——不会出现「名字改了、引用没跟上」的半成品。
     * [BatchOp.Remove] 对不存在的键是空操作。
     */
    fun batch(ops: List<BatchOp>, label: String, verb: String = "已写入", reveal: YPath? = null): Boolean {
        var cur = doc
        var touched = false
        val syncedAnchors = LinkedHashSet<String>()
        for (op in ops) {
            val next = when (op) {
                is BatchOp.Set -> {
                    if (!cur.canSet(op.path)) {
                        showToast("$label 未改动：${pathText(op.path)} 不在可编辑位置（下面是纯值 / 别名）", long = true)
                        return false
                    }
                    // 与 set() 同一套继承感知落地：写穿锚点 / 物化继承块 / 普通写
                    val w = AnchorInheritance.applySetAware(cur, op.path, op.value)
                    w.anchor?.let(syncedAnchors::add)
                    w.doc
                }
                is BatchOp.Remove -> if (cur.get(op.path) == null) continue else YamlPatch.removeKey(cur, op.path)
                is BatchOp.SetItem -> YamlPatch.setItem(cur, op.seqPath, op.index, op.value)
                is BatchOp.InsertItem -> YamlPatch.insertItem(cur, op.seqPath, op.index, op.value)
                is BatchOp.Rename -> {
                    val parent = cur.get(op.path.dropLast(1))
                    if (parent != null && cur.findEntry(parent, op.newKey) != null) {
                        showToast("已经有叫「${op.newKey}」的了，换个名字", long = true)
                        return false
                    }
                    YamlPatch.renameKey(cur, op.path, op.newKey)
                }
            }
            if (next === cur) {
                refuse(label)
                return false
            }
            cur = next
            touched = true
        }
        if (!touched) {
            showToast("$label 没有变化")
            return false
        }
        val named = if (syncedAnchors.isEmpty()) label else {
            val names = syncedAnchors.joinToString("、") { "&$it" }
            "$label（已同步到锚点 $names，继承它的所有条目一起生效）"
        }
        return commit(cur, named, verb, reveal)
    }

    // ---- 动态候选（下拉 / 多选里的节点名、代理组名、集合名）

    fun seqNames(seqPath: YPath): List<String> {
        val seq = doc.get(seqPath) ?: return emptyList()
        if (seq.kind != YamlNode.Kind.SEQ) return emptyList()
        return seq.items.mapNotNull { item -> FormValues.scalarText(doc, doc.findEntry(item, "name")?.node)?.ifBlank { null } }
    }

    fun mapKeys(mapPath: YPath): List<String> {
        val map = doc.get(mapPath) ?: return emptyList()
        if (map.kind != YamlNode.Kind.MAP) return emptyList()
        return map.entries.filter { !it.isMerge }.map { it.key }
    }

    private fun proxyEntries(): List<Pair<String, String?>> {
        val seq = doc.get(listOf("proxies")) ?: return emptyList()
        if (seq.kind != YamlNode.Kind.SEQ) return emptyList()
        return seq.items.mapNotNull { item ->
            val name = FormValues.scalarText(doc, doc.findEntry(item, "name")?.node)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val type = FormValues.scalarText(doc, doc.findEntry(item, "type")?.node)?.trim()?.lowercase()
            name to type
        }
    }

    private fun isSelectableProxy(name: String, type: String?): Boolean =
        name.uppercase() !in BUILTIN_POLICY_NAMES && type !in NON_SELECTABLE_PROXY_TYPES

    /**
     * 动态候选只提供当前配置里真实存在的项目；路由规则的 policies 保留 Mihomo 内置策略，
     * 代理选择 / 排除的 outbound、proxies 则过滤不可用协议和 DIRECT、REJECT 等内置策略。
     */
    fun candidates(kind: String, exclude: Set<String> = emptySet()): List<FormOption> {
        val seen = HashSet<String>()
        val out = ArrayList<FormOption>()
        fun add(v: String, label: String) {
            if (v.isEmpty() || v in exclude || !seen.add(v)) return
            out.add(FormOption(v, label))
        }
        fun addSelectableProxies() {
            for (n in seqNames(listOf("proxy-groups"))) {
                if (isSelectableProxy(n, null)) add(n, "代理组")
            }
            for ((name, type) in proxyEntries()) {
                if (isSelectableProxy(name, type)) add(name, "节点")
            }
        }
        when (kind) {
            "policies" -> {
                for (o in BUILTIN_POLICIES) add(o.value, o.label)
                for (n in seqNames(listOf("proxy-groups"))) add(n, "代理组")
                for ((name, _) in proxyEntries()) add(name, "节点")
                add("GLOBAL", "内置的全局代理组")
            }
            "outbound", "selectable-proxies" -> addSelectableProxies()
            "proxies" -> for ((name, type) in proxyEntries()) {
                if (isSelectableProxy(name, type)) add(name, "节点")
            }
            "providers" -> for (n in mapKeys(listOf("proxy-providers"))) add(n, "代理集合")
            "rule-providers" -> for (n in mapKeys(listOf("rule-providers"))) add(n, "规则集合")
            "sub-rules" -> for (n in mapKeys(listOf("sub-rules"))) add(n, "子规则")
        }
        return out
    }
}

// ---------------------------------------------------------------- hub

private class HubEntry(val title: String, val route: FormRoute, val countPath: String?, val countUnit: String)

private val HUB: List<HubEntry> = listOf(
    HubEntry("全局配置", FormRoute.Section("general"), null, ""),
    HubEntry("DNS", FormRoute.Section("dns"), "dns.enable", ""),
    HubEntry("域名嗅探", FormRoute.Section("sniff"), "sniffer.enable", ""),
    HubEntry("入站", FormRoute.Section("inbound"), "listeners", " 个监听器"),
    HubEntry("出站代理", FormRoute.Proxies(listOf("proxies"), "出站代理"), "proxies", " 个节点"),
    HubEntry("代理集合", FormRoute.Providers, "proxy-providers", " 个订阅"),
    HubEntry("代理组", FormRoute.Groups, "proxy-groups", " 个代理组"),
    HubEntry("路由规则", FormRoute.Rules(listOf("rules"), "路由规则"), "rules", " 条规则"),
    HubEntry("规则集合", FormRoute.RuleProviders, "rule-providers", " 个规则集"),
    HubEntry("子规则", FormRoute.SubRules, "sub-rules", " 组子规则"),
    HubEntry("流量隧道", FormRoute.Tunnels, "tunnels", " 条隧道"),
    HubEntry("NTP", FormRoute.Section("ntp"), "ntp.enable", ""),
    HubEntry("实验性配置", FormRoute.Section("experimental"), "experimental", ""),
)

@Composable
private fun FormHub(doc: YamlDoc, fileName: String, onOpen: (FormRoute) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "$fileName · 改动先落编辑器草稿，顶栏保存才写盘",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        items(HUB) { entry ->
            val count = entry.countPath?.let { countOf(doc, it) }
            val key = (entry.route as? FormRoute.Section)?.key
            val sub = buildString {
                if (count != null) {
                    when (key) {
                        "dns", "sniff", "ntp" -> append(if (count > 0) "已启用" else "未启用")
                        "experimental" -> append("$count 项已开")
                        else -> append("$count${entry.countUnit}")
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(),
                onClick = { onOpen(entry.route) },
            ) {
                BasicComponent(
                    title = entry.title,
                    summary = sub,
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Edit,
                            contentDescription = "编辑",
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }
}

private fun pageTitle(key: String): String = when (key) {
    "general" -> "全局配置"
    "dns" -> "DNS"
    "sniff" -> "域名嗅探"
    "inbound" -> "入站"
    "ntp" -> "NTP"
    "experimental" -> "实验性配置"
    else -> key
}

private fun sectionsOf(key: String): List<FormSection> = when (key) {
    "general" -> GENERAL_SECTIONS
    "dns" -> DNS_SECTIONS
    "sniff" -> SNIFF_SECTIONS
    "inbound" -> INBOUND_PORTS + TUN_SECTIONS
    "ntp" -> NTP_SECTIONS
    "experimental" -> EXPERIMENTAL_SECTIONS
    else -> emptyList()
}

// ---------------------------------------------------------------- 入站 listeners（eBPF 字段必须以 listeners[index] 为根）

@Composable
private fun InboundPage(host: FormHost, nav: FormNav) {
    SectionsPage(
        sections = INBOUND_PORTS + TUN_SECTIONS,
        base = emptyList(),
        host = host,
        footer = { ListenerListPage(host, nav) },
    )
}

// LISTENER_TYPES / LISTENER_TEMPLATES / 各协议字段表在 ListenerSpecs.kt（对齐参考实现 IN_* 全量类型）

private fun listenerName(doc: YamlDoc, index: Int): String =
    FormValues.readRaw(doc, listOf("listeners", index, "name")).orEmpty()

private fun listenerType(doc: YamlDoc, index: Int): String =
    FormValues.readRaw(doc, listOf("listeners", index, "type")).orEmpty()

private fun listenerNetworkChoice(doc: YamlDoc, path: YPath): String {
    val node = doc.get(path)
    val raw = if (node?.kind == YamlNode.Kind.SEQ) FormValues.readList(doc, path)
    else FormValues.readRaw(doc, path)?.let { listOf(it) }.orEmpty()
    val networks = raw.flatMap { it.replace("+", ",").split(',') }
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    return when {
        networks.isEmpty() -> ""
        networks.toSet() == setOf("tcp") -> "tcp"
        networks.toSet() == setOf("udp") -> "udp"
        networks.toSet() == setOf("tcp", "udp") -> "tcp+udp"
        else -> networks.joinToString(",")
    }
}

private fun ebpfRoleDescription(doc: YamlDoc, base: YPath): String {
    val (localOn, sharedOn) = ebpfRoleState(doc, base)
    return when {
        localOn && sharedOn -> "local + shared 已启用"
        localOn -> "仅 local（本机应用）已启用"
        sharedOn -> "仅 shared（热点 / 共享网络）已启用"
        else -> "两个角色都关闭 · 当前不接管流量"
    }
}

private fun listenerSummary(doc: YamlDoc, index: Int): String {
    val base = listOf("listeners", index)
    if (listenerType(doc, index) == "ebpf") return ebpfRoleDescription(doc, base)
    val address = FormValues.readRaw(doc, base + "listen") ?: "0.0.0.0"
    val ports = FormValues.readRaw(doc, base + "port")
        ?: FormValues.readList(doc, base + "ports").joinToString(",").takeIf { it.isNotEmpty() }
        ?: FormValues.readRaw(doc, base + "ports").orEmpty()
    val proxy = FormValues.readRaw(doc, base + "proxy")?.let { " → $it" }.orEmpty()
    return "$address:$ports$proxy"
}

private fun uniqueListenerName(doc: YamlDoc, type: String): String {
    val used = (0 until (doc.get(listOf("listeners"))?.items?.size ?: 0)).map { listenerName(doc, it) }.toSet()
    val base = "$type-in"
    if (base !in used) return base
    var index = 2
    while ("$base-$index" in used) index++
    return "$base-$index"
}

private fun listenerTemplate(doc: YamlDoc, type: String): Map<String, Any?> {
    val name = uniqueListenerName(doc, type)
    val nextPort = 7890L + (doc.get(listOf("listeners"))?.items?.size ?: 0)
    val tpl = LinkedHashMap<String, Any?>(LISTENER_TEMPLATES[type] ?: linkedMapOf("type" to type))
    tpl["name"] = name
    // 基础监听协议：端口按「7890 + 已有个数」顺延防撞；其余协议保留模板默认端口，进详情页再改
    if (type in LISTENER_BASIC_PORT_TYPES) tpl["port"] = nextPort
    return tpl
}

@Composable
private fun ListenerListPage(host: FormHost, nav: FormNav) {
    val doc = host.doc
    val path = listOf("listeners")
    val node = doc.get(path)
    val count = if (node?.kind == YamlNode.Kind.SEQ) node.items.size else 0
    var chooseType by remember(doc) { mutableStateOf(false) }
    var deleting by remember(doc) { mutableStateOf<Int?>(null) }
    // 按住把手拖动排序（对齐 mihomo_box WebUI 的规则 / 监听器列表）
    val dragSort = rememberDragSortState(count) { from, to ->
        host.moveItem(path, from, to, "监听器")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("监听器（$count）")
        if (node != null && node.kind != YamlNode.Kind.SEQ) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
                BasicComponent(
                    title = "listeners 不是列表",
                    summary = "当前形态不能安全追加监听器；请在 YAML 编辑器里修正为序列。",
                )
            }
        }
        if (count == 0) DialogNote("没有额外监听器。入站端口和 TUN 配置仍在本页上方编辑。")
        Column(
            modifier = Modifier.fillMaxWidth().then(dragSort.containerModifier()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (node?.kind == YamlNode.Kind.SEQ) {
                // 顺序由 DragSortState.order 定（拖动中实时换位）；行内容与回调按原始下标取
                dragSort.order.forEach { index ->
                    if (index !in node.items.indices) return@forEach
                    val name = listenerName(doc, index).ifBlank { "监听器 #${index + 1}" }
                    val type = listenerType(doc, index).ifBlank { "未知协议" }
                    DragSortRow(state = dragSort, index = index) {
                        RowCard(
                            title = name,
                            summary = "$type · ${listenerSummary(doc, index)}",
                            onClick = { nav.push(FormRoute.Listener(index)) },
                            dragHandle = { DragHandle(dragSort, index) },
                            endActions = {
                                // 与 mihomo_box 面板一致：行尾 ✎ 编辑 / × 删除两个图标按钮，
                                // 不再把「上移 / 下移 / 删除」藏进「⋯」菜单（排序改拖把手）
                                MiniIconButton(MiuixIcons.Edit, "编辑") { nav.push(FormRoute.Listener(index)) }
                                Spacer(Modifier.width(8.dp))
                                MiniIconButton(MiuixIcons.Delete, "删除") { deleting = index }
                            },
                        )
                    }
                }
            }
        }
        RowCard(
            title = "添加监听器",
            summary = "新增常用监听模板；eBPF 的角色 / 协议等参数在详情页编辑。",
            onClick = { chooseType = true },
            endActions = { TextButton(text = "添加", onClick = { chooseType = true }) },
        )
    }

    if (chooseType) {
        OptionPickerDialog(
            title = "选择监听器类型",
            options = LISTENER_TYPES,
            onDismiss = { chooseType = false },
            onPick = { type ->
                val value = listenerTemplate(doc, type)
                val newIndex = if (node?.kind == YamlNode.Kind.SEQ) node.items.size else 0
                val ok = if (node?.kind == YamlNode.Kind.SEQ) host.insertItem(path, newIndex, value, "监听器")
                else host.set(path, listOf(value), "监听器")
                chooseType = false
                if (ok) nav.push(FormRoute.Listener(newIndex))
            },
        )
    }
    deleting?.let { index ->
        ConfirmDialog(
            title = "删除监听器",
            text = "确定删除「${listenerName(doc, index).ifBlank { listenerType(doc, index) }}」吗？",
            confirmLabel = "删除",
            onDismiss = { deleting = null },
            onConfirm = {
                host.removeItem(path, index, "监听器")
                deleting = null
            },
        )
    }
}

private val COMMON_LISTENER_SECTIONS = listOf(
    FormSection("基础", listOf(
        FormField("name", "名称", FormFieldType.TEXT, optional = true),
        FormField("listen", "监听地址", FormFieldType.TEXT, placeholder = "0.0.0.0", optional = true),
        FormField("port", "端口", FormFieldType.NUMBER, optional = true),
        FormField("ports", "端口范围", FormFieldType.LIST, optional = true),
        FormField("udp", "启用 UDP", FormFieldType.BOOL, optional = true),
        FormField("network", "转发网络", FormFieldType.LIST, optional = true),
        FormField("target", "转发目标", FormFieldType.TEXT, optional = true),
        FormField("proxy", "出口代理", FormFieldType.SELECT, optionsDynamic = "outbound", optional = true, allowEmpty = true),
    )),
)

@Composable
private fun ListenerDetailsPage(index: Int, host: FormHost, nav: FormNav) {
    val doc = host.doc
    val base: YPath = listOf("listeners", index)
    val type = listenerType(doc, index)
    val listenerNode = doc.get(base)
    if (listenerNode == null || listenerNode.kind != YamlNode.Kind.MAP) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogNote("监听器 #${index + 1} 已不存在或不是映射。")
            TextButton(text = "返回", onClick = { nav.pop() })
        }
        return
    }
    val header: @Composable () -> Unit = {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
            BasicComponent(
                title = "协议类型：${type.ifBlank { "未知" }}",
                summary = if (type == "ebpf") "仅定制内核支持；保存配置后由内核校验生效。" else "通用监听参数可视化编辑；未列出的高级键会原样保留。",
                endActions = {
                    MiniIconButton(MiuixIcons.Delete, "删除") {
                        host.removeItem(listOf("listeners"), index, "监听器")
                        nav.pop()
                    }
                },
            )
        }
    }
    val nameGuard: FieldInterceptor = { field, _, value ->
        if (field.path == "name") {
            val newName = (value as? String).orEmpty().trim()
            val duplicate = doc.get(listOf("listeners"))?.items?.indices
                ?.any { it != index && listenerName(doc, it) == newName } == true
            if (newName.isNotEmpty() && duplicate) {
                showToast("已存在同名监听器「$newName」", long = true)
                true
            } else false
        } else false
    }
    if (type == "ebpf") {
        SectionsPage(
            sections = EBPF_SECTIONS,
            base = base,
            host = host,
            header = header,
            customRow = { row -> EbpfCustomRow(row, base, host) },
            dynamicOptions = { field, _ ->
                if (field.type == FormFieldType.RULESETPICK) host.candidates("rule-providers") else null
            },
            intercept = nameGuard,
        )
    } else {
        // 已知协议：按参考实现 IN_* 全量分组（基础 / 协议参数 / 传输层 / TLS / REALITY / 伪装 / Mux）；
        // 没见过的 type 退回旧的通用小节
        val sections = listenerSections(type) ?: COMMON_LISTENER_SECTIONS
        var usersEdit by remember(type, base) { mutableStateOf(false) }
        SectionsPage(
            sections = sections,
            base = base,
            host = host,
            header = header,
            dynamicOptions = { field, _ ->
                if (field.optionsDynamic == "outbound") host.candidates("outbound") else null
            },
            intercept = nameGuard,
            customRow = { row ->
                if (row.kind == "in-users") {
                    val count = FormValues.readMapList(doc, base + "users").size
                    RowCard(
                        title = "用户 users",
                        summary = when {
                            count > 0 -> "$count 个用户"
                            type in LISTENER_USERS_REQUIRED -> "未设置（该协议必填，至少一个用户）"
                            else -> "未设置"
                        },
                        onClick = { usersEdit = true },
                        endActions = { MiniIconButton(MiuixIcons.Edit, "编辑") { usersEdit = true } },
                    )
                }
            },
            footer = { DialogNote("表单未覆盖的高级参数保留在原 YAML，可用源码编辑器修改。") },
        )
        if (usersEdit) {
            val usersField = FormField(
                path = "users",
                label = if (type in LISTENER_USERS_REQUIRED) "用户（必填，至少一个）" else "用户",
                type = FormFieldType.MAPLIST,
                desc = when (type) {
                    "vmess" -> "UUID 为标准 36 位格式；alterId 新版填 0"
                    "vless" -> "UUID 为标准 36 位格式；另需 TLS 证书或 reality-config，否则内核拒绝启动"
                    "trojan" -> "另需 TLS 证书 / reality-config / ss-option 之一"
                    "http", "socks", "mixed" -> "留空则沿用全局 authentication；添加后仅对本入站生效"
                    else -> null
                },
                optional = type !in LISTENER_USERS_REQUIRED,
            )
            MapListDialog(
                field = usersField,
                columns = LISTENER_USERS_COLUMNS[type].orEmpty(),
                current = FormValues.readMapList(doc, base + "users"),
                onDismiss = { usersEdit = false },
                onConfirm = { items ->
                    usersEdit = false
                    // alterId 等数字列按数字写回（内核要求 int，字符串会启动失败）
                    val normalized = items.map { row ->
                        row.mapValues { (k, v) -> if (k == "alterId") (v.toString().toLongOrNull() ?: v) else v }
                    }
                    if (normalized.isEmpty() && type !in LISTENER_USERS_REQUIRED) host.clear(base + "users", "用户")
                    else host.set(base + "users", normalized, "用户")
                },
            )
        }
    }
}

@Composable
private fun EbpfCustomRow(row: FormCustomRow, base: YPath, host: FormHost) {
    val doc = host.doc
    val path: YPath = base + when (row.kind) {
        "ebpf.master" -> emptyList()
        "network" -> listOf("network")
        "fakeip-icmp" -> listOf("fakeip-icmp")
        "local.enable" -> listOf("local", "enable")
        "shared.enable" -> listOf("shared", "enable")
        "shared.interface" -> listOf("shared", "interface")
        else -> emptyList()
    }
    var dialog by remember(row.kind, base) { mutableStateOf(false) }
    val (localOn, sharedOn) = ebpfRoleState(doc, base)
    when (row.kind) {
        "ebpf.master" -> {
            val enabled = localOn || sharedOn
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
                BasicComponent(
                    title = "eBPF 入站总开关",
                    summary = when {
                        localOn && sharedOn -> "同时接管本机应用（local）与热点 / 共享网络（shared）"
                        !localOn && !sharedOn -> "两个角色都已关闭 · 不接管流量"
                        localOn -> "仅启用 local（本机应用）"
                        else -> "仅启用 shared（热点 / 共享网络）"
                    },
                    endActions = {
                        Switch(checked = enabled, onCheckedChange = { value -> setEbpfRoles(host, base, value, value) })
                    },
                    onClick = { setEbpfRoles(host, base, !enabled, !enabled) },
                )
            }
        }
        "local.enable", "shared.enable" -> {
            val role = if (row.kind == "local.enable") "local" else "shared"
            val roleOn = if (role == "local") localOn else sharedOn
            val otherOn = if (role == "local") sharedOn else localOn
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
                BasicComponent(
                    title = if (role == "local") "启用 local（本机应用）" else "启用 shared（热点 / 共享网络）",
                    summary = when {
                        roleOn -> if (role == "local") "接管本机应用的流量" else "接管下游接口转发的流量；需配置 shared.interface"
                        !otherOn -> "两个模式都关闭时 eBPF 不接管流量；其余参数仍保留"
                        else -> "当前由另一个角色接管流量"
                    },
                    endActions = {
                        Switch(checked = roleOn, onCheckedChange = { value ->
                            if (role == "local") setEbpfRoles(host, base, value, sharedOn)
                            else setEbpfRoles(host, base, localOn, value)
                        })
                    },
                    onClick = {
                        if (role == "local") setEbpfRoles(host, base, !roleOn, sharedOn)
                        else setEbpfRoles(host, base, localOn, !roleOn)
                    },
                )
            }
            if (role == "shared" && roleOn && !ebpfHasSharedInterface(doc, base)) {
                DialogNote("启用 shared / hybrid 前必须把 shared.interface 配成真实下联网卡；新建监听器不会猜网卡。")
            }
        }
        "shared.interface" -> {
            val interfaceField = remember {
                FormField(path = "shared.interface", label = "下游接口", type = FormFieldType.LIST)
            }
            val current = FormValues.readListField(doc, interfaceField, path).joinToString(",")
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
                BasicComponent(
                    title = "下游接口 shared.interface",
                    summary = if (current.isBlank()) "未设置（shared / hybrid 必填）" else tetherInterfaceSummary(current),
                    endActions = { TextButton(text = "检测 / 编辑", onClick = { dialog = true }) },
                    onClick = { dialog = true },
                )
            }
        }
        "network" -> {
            val current = listenerNetworkChoice(doc, path)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
                BasicComponent(
                    title = "监听协议 network",
                    summary = when (current) {
                        "" -> "默认（tcp + udp）"
                        "tcp" -> "仅 TCP"
                        "udp" -> "仅 UDP"
                        "tcp+udp" -> "TCP + UDP"
                        else -> "当前自定义：$current"
                    },
                    endActions = { TextButton(text = "选择", onClick = { dialog = true }) },
                    onClick = { dialog = true },
                )
            }
        }
        "fakeip-icmp" -> {
            val current = FormValues.readRaw(doc, path).orEmpty()
            val dnsV4 = FormValues.hasEffective(doc, listOf("dns", "fake-ip-range"))
            val dnsV6 = FormValues.hasEffective(doc, listOf("dns", "fake-ip-range6"))
            val icmpHook = ebpfHasFakeIpIcmpHook(doc, base, localOn, sharedOn)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
                BasicComponent(
                    title = "FakeIP ICMP fakeip-icmp",
                    summary = when (current) {
                        "off" -> "off · 不处理"
                        "reply" -> "reply · 内核直接回应 Echo"
                        else -> "默认（不覆写）"
                    },
                    endActions = { TextButton(text = "选择", onClick = { dialog = true }) },
                    onClick = { dialog = true },
                )
                if (current == "reply" && !dnsV4 && !dnsV6) DialogNote("reply 需要配置 dns.fake-ip-range 或 dns.fake-ip-range6。")
                if (current == "reply" && !icmpHook) DialogNote("reply 需要启用 local + local.data-plane=tc，或启用 shared 并配置 shared.interface。")
                DialogNote("reply 会在 TC 钩子上直接回应发往 fake-ip 的 ICMP Echo；默认 / off 不启用。")
            }
        }
        else -> Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
            BasicComponent(title = "专用控件：${row.kind}", summary = "不认识的定制行，未写入配置。")
        }
    }

    if (dialog && row.kind == "network") {
        val choices = listOf(
            FormOption("", "默认（tcp + udp）"),
            FormOption("tcp", "仅 TCP"),
            FormOption("udp", "仅 UDP"),
            FormOption("tcp+udp", "TCP + UDP"),
        )
        val current = listenerNetworkChoice(doc, path)
        OptionPickerDialog(
            title = "监听协议",
            options = choices,
            current = current,
            onDismiss = { dialog = false },
            onPick = { value ->
                dialog = false
                when (value) {
                    "" -> host.clear(path, "监听协议")
                    "tcp+udp" -> host.set(path, listOf("tcp", "udp"), "监听协议")
                    else -> host.set(path, listOf(value), "监听协议")
                }
            },
        )
    }
    if (dialog && row.kind == "fakeip-icmp") {
        val warnings = buildList {
            if (!FormValues.hasEffective(doc, listOf("dns", "fake-ip-range")) && !FormValues.hasEffective(doc, listOf("dns", "fake-ip-range6"))) {
                add("reply 需要配置 dns.fake-ip-range 或 dns.fake-ip-range6。")
            }
            if (!ebpfHasFakeIpIcmpHook(doc, base, localOn, sharedOn)) {
                add("reply 需要启用 local + local.data-plane=tc，或启用 shared 并配置 shared.interface。")
            }
        }
        OptionPickerDialog(
            title = "FakeIP ICMP",
            options = listOf(FormOption("", "默认（不覆写）"), FormOption("off", "off 不处理"), FormOption("reply", "reply 内核直接回应 Echo")),
            current = FormValues.readRaw(doc, path),
            summary = warnings.joinToString(" ").ifBlank { "reply 直接回应发往 fake-ip 的 ICMP Echo。" },
            onDismiss = { dialog = false },
            onPick = { value ->
                dialog = false
                if (value.isEmpty()) host.clear(path, "FakeIP ICMP") else host.set(path, value, "FakeIP ICMP")
            },
        )
    }

    if (dialog && row.kind == "shared.interface") {
        val interfaceField = FormField(path = "shared.interface", label = "下游接口", type = FormFieldType.LIST)
        val current = FormValues.readListField(doc, interfaceField, path).joinToString(",")
        TetherInterfaceEditDialog(
            show = true,
            title = "下游接口 shared.interface",
            initialValue = current,
            onDismiss = { dialog = false },
            onConfirm = { csv ->
                val values = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (values.isEmpty()) host.clear(path, "下游接口")
                else host.set(path, values, "下游接口")
            },
        )
    }
}

private fun setEbpfRoles(host: FormHost, base: YPath, localOn: Boolean, sharedOn: Boolean) {
    host.batch(ebpfRoleUpdateOps(base, localOn, sharedOn), "eBPF 角色开关", reveal = base)
}

// ---------------------------------------------------------------- 分区页（字段表 → 行）

/** 运行时候选钩子：详情页按字段 / 路径给出选择池（代理组排除自己、默认选中只列本组成员…）；返回 null 走通用规则。 */
internal typealias DynamicOptions = (field: FormField, path: YPath) -> List<FormOption>?

/**
 * 写入拦截钩子：详情页对个别字段做连带处理（切传输层顺手清掉别的 *-opts、开 TLS 补 servername、
 * 切集合类型删掉 file 用不到的键…）。`value == null` 表示「删键」；返回 true 表示已处理，FieldRow 不再写。
 */
internal typealias FieldInterceptor = (field: FormField, path: YPath, value: Any?) -> Boolean

/**
 * 把若干小节渲染成一页。`base` 是这些字段的根（P1 为空 = 顶层；P2 为 `proxies[3]` 这类项路径），
 * `onlyType` 给出当前项的 type 时，带 `only` 的字段按它过滤（代理组 / 集合的类型专属字段）；
 * 和参考实现一样，类型不匹配但配置里已经有值的字段仍然显示（能看见、能删，不会被静默藏起来）。
 */
@Composable
internal fun SectionsPage(
    sections: List<FormSection>,
    base: YPath,
    host: FormHost,
    onlyType: String? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    customRow: (@Composable (FormCustomRow) -> Unit)? = null,
    dynamicOptions: DynamicOptions? = null,
    hide: ((FormField) -> Boolean)? = null,
    intercept: FieldInterceptor? = null,
) {
    val doc = host.doc
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) item(key = "header") { header() }
        for (section in sections) {
            val rows = section.fields.filter { row ->
                when (row) {
                    is FormCustomRow -> true
                    is FormField -> (hide == null || !hide(row)) && (
                        row.only.isEmpty() || (onlyType != null && onlyType in row.only) ||
                            FormValues.hasValue(doc, base + YamlDoc.splitPath(row.path))
                        )
                }
            }
            if (rows.isEmpty()) continue
            item(key = "h-${section.title}") { SectionHeader(section.title) }
            items(rows) { row ->
                when (row) {
                    is FormCustomRow -> if (customRow != null) customRow(row) else CustomRow(row)
                    is FormField -> FieldRow(row, base, host, dynamicOptions, intercept)
                }
            }
        }
        if (footer != null) item(key = "footer") { footer() }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun CustomRow(row: FormCustomRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
    ) {
        BasicComponent(
            title = "专门控件：${row.kind}",
            summary = "P3 实现（eBPF 角色开关等由代码定制的行）",
            onClick = { showToast("${row.kind} 的专用编辑器在 P3 落地，先用编辑器直接改这一段原文", long = true) },
        )
    }
}

/**
 * 一行普通内容卡（列表页里的项）。`dragHandle` 给了就把拖动把手挂在行首（列表排序用）。
 *
 * [titleMaxLines] / [summaryMaxLines] 默认不限行（老调用点行为一字不变）。路由规则那种「标题里
 * 直接带着匹配值」的列表会传 1：一条 DOMAIN-SUFFIX 后面挂几十个域名、GEOIP 参数很长时，整行会
 * 被撑成好几行，传 1 就每行只留一行、末尾打省略号。
 */
@Composable
internal fun RowCard(
    title: String,
    summary: String?,
    onClick: (() -> Unit)?,
    dragHandle: (@Composable () -> Unit)? = null,
    titleMaxLines: Int = Int.MAX_VALUE,
    summaryMaxLines: Int = Int.MAX_VALUE,
    endActions: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
    ) {
        if (titleMaxLines == Int.MAX_VALUE && summaryMaxLines == Int.MAX_VALUE) {
            BasicComponent(
                title = title,
                summary = summary,
                startAction = dragHandle,
                endActions = { endActions() },
                onClick = onClick,
            )
        } else {
            // miuix 的 BasicComponent(title, summary) 内部 Text 没有 maxLines / overflow 出口
            // （0.9.4 只有 fontSize / fontWeight / color），要限行只能走 content 版本，
            // 字号、字重、颜色照 BasicComponentDefaults / MiuixTheme.textStyles 抄下来保持一致。
            val titleColor = BasicComponentDefaults.titleColor()
            val summaryColor = BasicComponentDefaults.summaryColor()
            BasicComponent(
                startAction = dragHandle,
                endActions = { endActions() },
                onClick = onClick,
            ) {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = titleColor.color,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = summaryColor.color,
                        maxLines = summaryMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 列表页顶部的说明 + 操作按钮行。 */
@Composable
internal fun ListHeader(summary: String, actions: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(
            text = summary,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            // spacedBy 兜底：headerActions 里多个按钮相邻时统一间距，调用方不用再手垫 Spacer
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) { actions() }
    }
}

private val P3_ONLY = setOf(
    // 当前字段表中只保留了暂未用到的交互式 checkbox / file / button 扩展类型；
    // P3 常用控件（DNS / APPLIST / FAKEIPRULE / RULESETPICK / MAPLIST / HEADERS）已由专用编辑器接通。
    FormFieldType.CHECKBOX, FormFieldType.FILE, FormFieldType.BUTTON,
)

/** 简版映射列表编辑器知道怎么编辑的 MAPLIST：字段路径 → 各列（键，标签）。其它 MAPLIST 仍是 P3。 */
private val MAPLIST_COLUMNS: Map<String, List<Pair<String, String>>> = mapOf(
    "override.proxy-name" to listOf("pattern" to "匹配正则 pattern", "target" to "替换为 target"),
)

/** 字段表没写 optionsDynamic 时的兜底：节点自己的 dialer-proxy 用出站池（详情页一般会用 dynamicOptions 钩子再排除自己）。 */
private fun dynamicKind(field: FormField): String? =
    field.optionsDynamic ?: if (field.options.isEmpty() && field.type == FormFieldType.SELECT &&
        field.path.endsWith("dialer-proxy")
    ) "outbound" else null

private fun isP3(field: FormField): Boolean = field.type in P3_ONLY

/** 文本输入要写回的值：数字字段写数字（整数优先），其余照字符串写。 */
internal fun typedInput(field: FormField, v: String): Any? =
    if (field.type == FormFieldType.NUMBER || field.numeric) (v.toLongOrNull() ?: v.toDoubleOrNull() ?: v) else v

private fun isInvalidNumericInput(field: FormField, value: String): Boolean =
    (field.type == FormFieldType.NUMBER || field.numeric) &&
        value.toLongOrNull() == null && value.toDoubleOrNull() == null

/** 下拉选中值要写回的类型（参考实现 fieldRow：`num` → 数字，`bool` → 布尔）。 */
private fun selectValue(field: FormField, v: String): Any? = when {
    field.numeric -> v.toLongOrNull() ?: v.toDoubleOrNull() ?: v
    field.boolKind != null -> v.equals("true", ignoreCase = true)
    else -> v
}

private fun selectCurrent(field: FormField, doc: YamlDoc, path: YPath): String? {
    val raw = FormValues.readRaw(doc, path) ?: return null
    return if (field.boolKind != null) field.options.firstOrNull { it.value.equals(raw, ignoreCase = true) }?.value ?: raw else raw
}

private val LIST_TYPES = setOf(FormFieldType.LIST, FormFieldType.NUMLIST, FormFieldType.USERLIST, FormFieldType.PICKLIST)

/** 三态布尔（参考实现：声明了默认值、tri、或 boolAs=pick 的开关都走「默认 / 开 / 关」弹窗）。 */
private fun isTriBool(field: FormField): Boolean =
    field.type == FormFieldType.BOOL && !field.asSwitch && (field.default != null || field.tri || field.boolAs == "pick")

/** 开关的显示值（组合期与点击期共用）：asSwitch 未设置时显示默认值，其余按文档里的布尔。 */
private fun switchShownOf(doc: YamlDoc, field: FormField, path: YPath): Boolean {
    if (field.type != FormFieldType.BOOL) return false
    val has = FormValues.hasValue(doc, path)
    return if (field.asSwitch && !has) field.default == true else has && FormValues.readBool(doc, path) == true
}

/**
 * 一个字段一行：标题 + 当前值摘要 + 右侧控件（开关 / 编辑）。字段的真实路径 = `base` + 字段表里的相对路径。
 * 行为对齐参考实现 fields.js 的 fieldRow（见各分支注释）。
 */
@Composable
internal fun FieldRow(
    field: FormField,
    base: YPath,
    host: FormHost,
    dynamicOptions: DynamicOptions? = null,
    intercept: FieldInterceptor? = null,
) {
    val doc = host.doc
    val path: YPath = remember(field.path, base) { base + YamlDoc.splitPath(field.path) }
    val editable = doc.canSet(path)
    val hasVal = FormValues.hasValue(doc, path)
    var dialog by remember(field.path, base) { mutableStateOf(false) }
    val p3 = isP3(field)
    val tri = isTriBool(field)
    val switchShown = switchShownOf(doc, field, path)

    // 回调只读点击那一刻的状态，不捕获本次组合的 host / doc / field / path：Miuix Switch 用
    // rememberUpdatedState 持有 onCheckedChange，而局部函数引用（::setSwitchValue）跨组合 equals 恒为真，
    // State 不会替换，闭包会一直停在首次组合（旧文档快照）。下面这些 State 由 remember 持有、跨组合是同一对象。
    val latestHost by rememberUpdatedState(host)
    val latestField by rememberUpdatedState(field)
    val latestPath by rememberUpdatedState(path)
    val latestIntercept by rememberUpdatedState(intercept)

    /** 统一出口：先问详情页的拦截钩子，没人管再按「null = 删键，其余 = 写值」落到 FormHost。 */
    fun write(value: Any?): Boolean {
        val f = latestField
        val p = latestPath
        val icp = latestIntercept
        if (icp != null && icp(f, p, value)) return true
        val h = latestHost
        return if (value == null) h.clear(p, f.label) else h.set(p, value, f.label)
    }
    // 源码无本地行但展开视图（锚点继承）有值时照配置参数显示，尾标「继承」——可视化不丢继承来的参数
    val inherited = if (hasVal) null else FormValues.describeEffective(doc, field, path)
    val summary = when {
        field.type == FormFieldType.BOOL -> {
            val v = FormValues.readBool(doc, path)
            when {
                hasVal && v != null -> if (v) "开" else "关"
                hasVal -> FormValues.readRaw(doc, path).orEmpty().take(40)
                inherited != null -> inherited
                field.asSwitch -> "未设置（默认${if (field.default == true) "开" else "关"}）"
                field.default != null -> "默认（不覆写，内核默认${if (field.default) "开" else "关"}）"
                else -> if (tri) "默认（不覆写）" else "未设置"
            }
        }
        else -> {
            val local = FormValues.describe(doc, field, path)
            if (local == "未设置" && inherited != null) inherited else local
        }
    }

    fun openEditor() {
        val f = latestField
        val d = latestHost.doc
        when {
            isP3(f) -> showToast("${f.label} 暂不支持可视化编辑，请在 YAML 编辑器里直接改这一段", long = true)
            !d.canSet(latestPath) -> showToast("${f.label} 不在可编辑位置（该路径下面是纯值 / 别名），请在编辑器里直接改", long = true)
            else -> dialog = true
        }
    }

    /** 开关 / 整行点击的统一出口：「本来就……」的判断与写回都针对点击时的文档。 */
    fun setSwitchValue(value: Boolean) {
        val f = latestField
        val p = latestPath
        val d = latestHost.doc
        if (!d.canSet(p)) {
            showToast("${f.label} 不在可编辑位置（该路径下面是纯值 / 别名），请在编辑器里直接改", long = true)
            return
        }
        val has = FormValues.hasValue(d, p)
        if (f.asSwitch) {
            if (value == (f.default == true) && !has) showToast("${f.label} 本来就是默认值")
            else write(value)
        } else {
            if (!value && f.optional && !has) showToast("${f.label} 本来就没有设置")
            else write(value)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
    ) {
        BasicComponent(
            title = field.label,
            summary = buildString {
                if (summary.isNotEmpty()) append(summary)
                field.desc?.let { if (isNotEmpty()) append(" · "); append(it) }
                if (!editable) { if (isNotEmpty()) append(" · "); append("该路径下面是纯值/别名，不能在这里改") }
                field.tag?.let { if (isNotEmpty()) append(" · "); append(it) }
            },
            endActions = {
                when {
                    field.type == FormFieldType.BOOL && field.asSwitch -> {
                        Switch(
                            checked = switchShown,
                            onCheckedChange = ::setSwitchValue,
                            enabled = editable,
                        )
                    }
                    field.type == FormFieldType.BOOL && !tri -> {
                        Switch(
                            checked = switchShown,
                            onCheckedChange = ::setSwitchValue,
                            enabled = editable,
                        )
                    }
                    p3 -> Text(text = "P3", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    // 与 mihomo_box 面板一致：编辑入口用小图标按钮（✎），不再占一列文字
                    else -> if (tri) {
                        TextButton(text = "选择", onClick = { openEditor() })
                    } else {
                        MiniIconButton(MiuixIcons.Edit, "编辑") { openEditor() }
                    }
                }
            },
            onClick = {
                if (field.type == FormFieldType.BOOL && !tri) {
                    setSwitchValue(!switchShownOf(latestHost.doc, latestField, latestPath))
                } else {
                    openEditor()
                }
            },
        )
    }

    if (dialog) {
        val close = { dialog = false }
        when {
            tri -> TriBoolDialog(
                title = field.label,
                desc = field.desc,
                current = if (hasVal) FormValues.readBool(doc, path) else null,
                onDismiss = close,
                onPick = { v ->
                    close()
                    if (v == null) { if (hasVal) write(null) else showToast("${field.label} 本来就是默认") }
                    else write(v)
                },
            )
            field.type == FormFieldType.SELECT -> SelectDialog(
                field = field,
                current = selectCurrent(field, doc, path),
                candidates = dynamicOptions?.invoke(field, path)
                    ?: dynamicKind(field)?.let { host.candidates(it) }
                    ?: emptyList(),
                hideUnavailableCurrent = dynamicKind(field) in setOf("outbound", "selectable-proxies", "proxies") ||
                    field.optionsDynamic == "outbound",
                onDismiss = close,
                onPick = { value ->
                    close()
                    if (value == null) write(null)
                    else write(selectValue(field, value))
                },
            )
            field.type in LIST_TYPES -> {
                val current = FormValues.readListField(doc, field, path)
                val kind = dynamicKind(field)
                val candidates = dynamicOptions?.invoke(field, path)
                    ?: kind?.let { host.candidates(it) }
                    ?: field.options
                // 参考实现：带候选清单（datalist / 动态池）的列表只能挑选，不能手填
                val pickOnly = field.type == FormFieldType.PICKLIST || kind != null || field.options.isNotEmpty() || dynamicOptions?.invoke(field, path) != null
                ListDialog(
                    field = field,
                    current = current,
                    candidates = candidates,
                    pickOnly = pickOnly,
                    onDismiss = close,
                    onConfirm = { picked ->
                        close()
                        val items = picked.distinct()
                        when {
                            items.isEmpty() && field.optional -> write(null)
                            field.join != null -> write(items.joinToString(field.join))
                            field.type == FormFieldType.NUMLIST -> write(items.map { it.toLongOrNull() ?: it })
                            else -> write(items)
                        }
                    },
                )
            }
            field.type == FormFieldType.DNSLIST -> DnsServerListDialog(
                title = field.label,
                ipOnly = field.dnsIpOnly,
                current = FormValues.readListField(doc, field, path),
                note = field.desc,
                onDismiss = close,
                onConfirm = { servers ->
                    close()
                    if (servers.isEmpty() && field.optional) write(null) else write(servers)
                },
            )
            field.type == FormFieldType.APPLIST -> AppListFieldDialog(
                field = field,
                current = FormValues.readListField(doc, field, path),
                onDismiss = close,
                onConfirm = { packages ->
                    close()
                    if (packages.isEmpty() && field.optional) write(null) else write(packages.distinct())
                },
            )
            field.type == FormFieldType.RULESETPICK -> RuleSetPickDialog(
                field = field,
                current = FormValues.readListField(doc, field, path),
                candidates = host.candidates("rule-providers"),
                onDismiss = close,
                onConfirm = { selected ->
                    close()
                    if (selected.isEmpty() && field.optional) write(null) else write(selected.distinct())
                },
            )
            field.type == FormFieldType.FAKEIPRULE -> FakeIpRulesDialog(
                field = field,
                current = FormValues.readListField(doc, field, path),
                mode = FormValues.readRaw(doc, listOf("dns", "fake-ip-filter-mode")).orEmpty(),
                onDismiss = close,
                onConfirm = { rules ->
                    close()
                    if (rules.isEmpty() && field.optional) write(null) else write(rules)
                },
            )
            field.type == FormFieldType.MAPTEXT -> MapDialog(
                field = field,
                current = FormValues.readMap(doc, path),
                onDismiss = close,
                onConfirm = { map ->
                    close()
                    if (map.isEmpty() && field.optional) write(null) else write(map)
                },
            )
            field.type == FormFieldType.HEADERS -> HeadersEditorDialog(
                field = field,
                current = FormValues.readHeaderRows(doc, path),
                onDismiss = close,
                onConfirm = { map ->
                    close()
                    commitMapValues(host, path, FormValues.readHeaderMap(doc, path), map, field.label, field.optional)
                },
            )
            field.type == FormFieldType.MAPLIST && field.path in MAPLIST_COLUMNS -> MapListDialog(
                field = field,
                columns = MAPLIST_COLUMNS[field.path].orEmpty(),
                current = FormValues.readMapList(doc, path),
                onDismiss = close,
                onConfirm = { items ->
                    close()
                    if (items.isEmpty() && field.optional) write(null) else write(items)
                },
            )
            field.type == FormFieldType.MAPLIST -> MapListFieldDialog(
                field = field,
                current = FormValues.readMapListRows(doc, path, splitScalar = field.path == "hosts"),
                onDismiss = close,
                onConfirm = { edits ->
                    close()
                    commitMapListEdits(host, path, edits, field)
                },
            )
            field.type == FormFieldType.TEXTAREA -> TextValueDialog(
                title = field.label,
                summary = pathText(path) + (field.hint?.let { " · $it" } ?: ""),
                desc = field.desc ?: field.placeholder?.let { "例：$it" },
                current = FormValues.readRaw(doc, path).orEmpty(),
                multiLine = true,
                onDismiss = close,
                onConfirm = { v ->
                    close()
                    // 参考实现：空 → 可选字段删键，必填字段写 ''
                    if (v.isBlank()) { if (field.optional) write(null) else write("") }
                    else write(v)
                },
            )
            else -> TextValueDialog(
                title = field.label,
                summary = pathText(path) + (field.hint?.let { " · $it" } ?: ""),
                desc = field.desc ?: field.placeholder?.let { "例：$it" },
                current = FormValues.readRaw(doc, path).orEmpty(),
                multiLine = false,
                onDismiss = close,
                onConfirm = { v ->
                    close()
                    when {
                        v.isEmpty() -> if (field.optional) write(null) else write("")
                        isInvalidNumericInput(field, v) -> showToast("${field.label} 需要是数字，已忽略", long = true)
                        else -> write(typedInput(field, v))
                    }
                },
            )
        }
    }
}

/**
 * 逐键 diff 回写映射，未编辑的子节点保持原文（注释、锚点和值形态不会因重建整张 map 丢掉）。
 */
private fun commitMapValues(
    host: FormHost,
    path: YPath,
    before: Map<String, Any?>,
    after: Map<String, Any?>,
    label: String,
    optional: Boolean,
): Boolean {
    if (after.isEmpty()) {
        return if (optional) host.clear(path, label) else host.set(path, emptyMap<String, Any?>(), label)
    }
    val ops = ArrayList<BatchOp>()
    before.keys.filter { it !in after }.forEach { ops += BatchOp.Remove(path + it) }
    for ((key, value) in after) {
        if (key !in before || before[key] != value) ops += BatchOp.Set(path + key, value)
    }
    return if (ops.isEmpty()) {
        showToast("$label 没有变化")
        false
    } else host.batch(ops, label, reveal = path)
}

/**
 * Maplist 行级 diff：删除、改名和更新都落在具体子键，不把整张映射重新 dump；已有别名/注释的其它项不动。
 */
private fun commitMapListEdits(
    host: FormHost,
    path: YPath,
    edits: List<FormMapListEdit>,
    field: FormField,
): Boolean {
    if (edits.any { it.source?.supported == false }) {
        showToast("${field.label} 含有表单不支持的嵌套映射 / 别名项，未写入", long = true)
        return false
    }
    val duplicates = edits.map { it.key }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) {
        showToast("映射键重复：${duplicates.joinToString()}，未写入", long = true)
        return false
    }
    if (edits.isEmpty()) {
        return if (field.optional) host.clear(path, field.label)
        else host.set(path, emptyMap<String, Any?>(), field.label)
    }

    val oldRows = FormValues.readMapListRows(host.doc, path, splitScalar = field.path == "hosts")
    val ops = buildMapListEditOps(path, oldRows, edits) ?: return false
    return if (ops.isEmpty()) {
        showToast("${field.label} 没有变化")
        false
    } else host.batch(ops, field.label, reveal = path)
}

/** 路径的可读形式：`proxies[3].ws-opts.path`。 */
internal fun pathText(path: YPath): String = buildString {
    for (seg in path) {
        if (seg is Int) append('[').append(seg).append(']')
        else { if (isNotEmpty()) append('.'); append(YamlDoc.quoteSeg(seg.toString())) }
    }
}

// ---------------------------------------------------------------- 取值 / 计数

private fun countOf(doc: YamlDoc, path: String): Int {
    val n = doc.get(path) ?: return 0
    return when (n.kind) {
        YamlNode.Kind.MAP -> n.entries.size
        YamlNode.Kind.SEQ -> n.items.size
        else -> if (FormValues.readBool(doc, YamlDoc.splitPath(path)) == true) 1 else 0
    }
}

/** 写入后定位到该路径所在行（1 起），找不到就返回 null。 */
private fun lineOfPath(doc: YamlDoc, path: YPath): Int? {
    var node: YamlNode? = doc.root
    var line: Int? = null
    for (part in path) {
        val n = node ?: break
        if (n.flow) return n.start + 1
        if (part is Int) {
            if (n.kind != YamlNode.Kind.SEQ || part < 0 || part >= n.items.size) return line?.plus(1)
            node = n.items[part]
            line = node.start
        } else {
            val entry = doc.findEntry(n, part.toString()) ?: return line?.plus(1)
            line = entry.line
            node = entry.node
        }
    }
    return line?.plus(1)
}
