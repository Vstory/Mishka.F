package top.yukonga.mishka.custom.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 外部面板（Web 界面）——box.app `PanelScreen` 的 Mishka 移植版。
 *
 * 面板本身是纯前端页面，靠浏览器连 mihomo 的 external controller 拿数据；本页只负责三件事：
 *  1. 把「本地面板」的地址算出来（`http://<控制器>/ui`，端口取自 [ProxyServiceBridge]）
 *  2. 用一个配置好的 WebView 承载它（放开 mixed content、跟随深色、接管下载与文件选择）
 *  3. 管好面板清单：三个内置源 + 自定义增删，选择持久在 [PanelStore]
 *
 * **不代填控制器地址与密钥**：与 box.app 一致，面板自己会在首次进入时让用户填一次并存进
 * localStorage。MIUI/ HyperOS 上各家面板对 `?hostname=&secret=` 的支持并不一致，代填错了
 * 反而比不填更难排查。
 *
 * **顶栏对齐 box.app 的布局、颜色跟随 App 主题**：单行布局——`ChevronBackward` 返回、18sp
 * 左对齐标题、刷新 / 面板清单（`Tune` 滑杆）/ 清除数据；底色用 `MiuixTheme.colorScheme.surface`
 * 、标题与图标用 `onSurface`，和 App 其它顶栏同色系、随深浅色主题走。顶栏铺到状态栏后面，
 * 状态栏图标外观交给 [top.yukonga.mishka.PanelActivity] 的主题化 enforce，不再强制。
 *
 * **宿主是独立 Activity**（[top.yukonga.mishka.PanelActivity]，不在主界面导航栈里）：每次
 * 进入都是新 WebView + 加载入口（重进总是面板首页，页内历史可清），返回先退页内历史、
 * 退无可退由系统收尾 Activity；防白闪靠组件的 `hideUntilCommitVisible`（内容提交前隐藏
 * WebView、只露底色），换面板加载完清 history。新实例由 [WebViewPreloader] 秒建。
 *
 * @param onBack 顶栏返回 / 页内历史退完后的收尾，由宿主 Activity 传 `finish()`
 * @param proxyStatus 进入面板时的代理决议（[PanelProxyOverride]）；代理没接上时顶部给出提示
 */
@Composable
fun PanelScreen(
    onBack: () -> Unit = {},
    proxyStatus: PanelProxyStatus? = null,
) {
    val context = LocalContext.current
    // 跟随 App 主题（宿主 Activity 用 MishkaTheme 提供），交给 WebView 的算法深色
    val isDark = LocalAppDarkMode.current
    val scope = rememberCoroutineScope()
    val status by ProxyServiceBridge.state.collectAsStateWithLifecycle()

    val localName = stringResource(R.string.panel_local_name)
    val localPanel = remember(localName) { PanelEntry(PANEL_ID_LOCAL, localName, null, isBuiltIn = true) }
    val zashboardPanel = remember {
        PanelEntry(PANEL_ID_ZASHBOARD, "Zashboard", PANEL_URL_ZASHBOARD, isBuiltIn = true)
    }
    val metaCubeXdPanel = remember {
        PanelEntry(PANEL_ID_METACUBEXD, "MetaCubeXD", PANEL_URL_METACUBEXD, isBuiltIn = true)
    }

    val customPanels: SnapshotStateList<PanelEntry> = remember {
        mutableStateListOf<PanelEntry>().apply { addAll(PanelStore.customPanels(context)) }
    }
    val panels = listOf(localPanel, zashboardPanel, metaCubeXdPanel) + customPanels

    var selectedId by rememberSaveable { mutableStateOf(PanelStore.selectedId(context)) }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    var sessionKey by rememberSaveable { mutableIntStateOf(0) }
    var backRequestKey by rememberSaveable { mutableIntStateOf(0) }
    var pageTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // 瞬态状态不跨「返回 / 重进」保存（box.app openSubpage 进入即清零的等价语义）：
    // 每次进入都是新实例重新加载，恢复一个 true 的 refreshing 会让顶栏转圈永远
    // 停不下来；canGoBack 从「不能返回」起步，等加载 / 历史变化回报真实值
    var webError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var proxyNoticeDismissed by remember { mutableStateOf(false) }

    /**
     * 本地面板地址。控制器端口只在 [ProxyState.Running] 时才是「正在监听的那个」——
     * 停止态下 [top.yukonga.mishka.platform.ProxyServiceStatus.externalController] 是默认值，
     * 拿它拼 URL 会指向一个没人听的端口，所以停了就退回上次缓存的地址。
     */
    val liveLocalUrl = if (status.state == ProxyState.Running) {
        "http://${status.externalController}/ui"
    } else {
        null
    }
    LaunchedEffect(liveLocalUrl) {
        liveLocalUrl?.let { PanelStore.cacheLocalUrl(context, it) }
    }

    val selectedPanel = panels.firstOrNull { it.id == selectedId } ?: localPanel
    val panelUrl = if (selectedPanel.id == PANEL_ID_LOCAL) {
        liveLocalUrl ?: PanelStore.cachedLocalUrl(context)
    } else {
        selectedPanel.url
    }

    fun applySelected(panel: PanelEntry) {
        if (selectedId != panel.id) {
            selectedId = panel.id
            PanelStore.saveSelectedId(context, panel.id)
            // 换面板要连 history 和 JS 全局一起丢掉，光 loadUrl 会带上一家的登录态
            sessionKey += 1
        }
        pageTitle = null
        webError = null
        canGoBack = false
    }

    val backNavState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backNavState,
        isBackEnabled = canGoBack,
        onBackCompleted = { backRequestKey += 1 },
    )

    val notRunningHint = stringResource(R.string.panel_local_not_running)
    val clearedMsg = stringResource(R.string.common_cleared)
    val clearFailedMsg = stringResource(R.string.panel_clear_cache_failed)

    Scaffold(
        topBar = {
            PanelTopBar(
                title = pageTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.panel_title),
                refreshing = refreshing,
                refreshEnabled = panelUrl != null,
                clearEnabled = !clearing,
                onBack = onBack,
                onRefresh = {
                    webError = null
                    refreshing = true
                    reloadKey += 1
                },
                onShowPanels = { showSheet = true },
                onClear = { showClearDialog = true },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (panelUrl != null) {
                PanelWebView(
                    url = panelUrl,
                    isDark = isDark,
                    reloadKey = reloadKey,
                    sessionKey = sessionKey,
                    backRequestKey = backRequestKey,
                    hideUntilCommitVisible = true,
                    resetHistoryOnUrlChange = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    onPageFinished = { refreshing = false },
                    onTitleChange = { pageTitle = it },
                    onWebError = { webError = it },
                    onCanGoBackChange = { canGoBack = it },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = notRunningHint,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // 错误横幅浮在内容上：面板自己的报错页常常是一片空白，不给提示就是「点了没反应」。
            // 代理没接上（拿不到 mixed-port / WebView 不支持代理覆盖）时外网仍是直连，同样要提示；点按即可关闭
            val errorMessage = webError
            val showProxyNotice = proxyStatus == PanelProxyStatus.Unavailable &&
                !proxyNoticeDismissed && errorMessage.isNullOrBlank()
            val bannerText = if (showProxyNotice) stringResource(R.string.panel_proxy_unavailable) else errorMessage
            if (!bannerText.isNullOrBlank()) {
                Card(
                    modifier = Modifier
                        .padding(top = innerPadding.calculateTopPadding())
                        .padding(12.dp)
                        .fillMaxWidth()
                        .clickable(enabled = showProxyNotice) { proxyNoticeDismissed = true },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bannerText,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            color = if (showProxyNotice) StatusColors.warning else StatusColors.danger,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        WindowBottomSheet(
            show = true,
            title = stringResource(R.string.panel_title),
            onDismissRequest = { showSheet = false },
        ) {
            PanelSheetContent(
                panels = panels,
                selectedId = selectedPanel.id,
                onSelect = { applySelected(it) },
                onDelete = { panel ->
                    customPanels.removeAll { it.id == panel.id }
                    PanelStore.saveCustomPanels(context, customPanels)
                    if (selectedId == panel.id) applySelected(localPanel)
                },
                onAdd = { name, url ->
                    val entry = PanelEntry(
                        id = "custom_${System.currentTimeMillis()}",
                        name = name,
                        url = url,
                        isBuiltIn = false,
                    )
                    customPanels.add(entry)
                    PanelStore.saveCustomPanels(context, customPanels)
                    true
                },
            )
        }
    }

    if (showClearDialog) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.panel_clear_cache),
            onDismissRequest = { showClearDialog = false },
        ) {
            Text(
                text = stringResource(R.string.panel_clear_cache_message),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showClearDialog = false },
                )
                TextButton(
                    text = stringResource(R.string.common_clear),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showClearDialog = false
                        if (clearing) return@TextButton
                        clearing = true
                        scope.launch {
                            val ok = clearWebViewAppData(context)
                            showToast(if (ok) clearedMsg else clearFailedMsg)
                            if (ok) {
                                webError = null
                                reloadKey += 1
                            }
                            clearing = false
                        }
                    },
                )
            }
        }
    }
}

/** 标题行高（不含状态栏），与 box.app 顶栏一致。 */
private val PanelBarHeight = 56.dp

/**
 * box.app 同款布局的顶栏：单行——`ChevronBackward` 返回、18sp 左对齐标题（超长省略）、
 * 刷新 / 面板清单（`Tune` 滑杆）/ 清除数据。四个 40dp 图标槽与标题间距按 box.app
 * 实测像素复刻：外沿 16dp、标题距返回槽 12dp。
 *
 * 颜色跟随 App 主题：底色 [top.yukonga.miuix.kmp.theme.Colors.surface]、标题与图标
 * [top.yukonga.miuix.kmp.theme.Colors.onSurface]，和 [top.yukonga.mishka.ui.component.AdaptiveTopAppBar]
 * 同一套 token，深浅色主题下都与 App 其它页面一致。
 */
@Composable
private fun PanelTopBar(
    title: String,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    clearEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onShowPanels: () -> Unit,
    onClear: () -> Unit,
) {
    val barColor = MiuixTheme.colorScheme.surface
    val contentColor = MiuixTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PanelBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.ChevronBackward,
                    contentDescription = stringResource(R.string.common_back),
                    tint = contentColor,
                )
            }
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                color = contentColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                if (refreshing) {
                    CircularProgressIndicator(
                        size = 20.dp,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.common_refresh),
                        tint = contentColor,
                    )
                }
            }
            IconButton(onClick = onShowPanels) {
                Icon(
                    imageVector = MiuixIcons.Tune,
                    contentDescription = stringResource(R.string.panel_title),
                    tint = contentColor,
                )
            }
            IconButton(onClick = onClear, enabled = clearEnabled) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.panel_clear_cache),
                    tint = contentColor,
                )
            }
        }
    }
}