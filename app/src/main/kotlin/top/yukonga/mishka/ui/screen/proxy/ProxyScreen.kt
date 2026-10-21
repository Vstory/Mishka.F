package top.yukonga.mishka.ui.screen.proxy

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardSegment
import top.yukonga.mishka.ui.component.ListPopupDefaults.MenuPositionProvider
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.platform.IconLoader
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.viewmodel.ProxyGroupUi
import top.yukonga.mishka.viewmodel.ProxyUiState
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    viewModel: ProxyViewModel? = null,
) {
    val uiState = viewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: ProxyUiState()
    val sortOption = viewModel?.sortOption?.collectAsStateWithLifecycle()?.value ?: 0
    val singleColumn = viewModel?.singleColumn?.collectAsStateWithLifecycle()?.value == true
    // 0 = 行内两列并排，1 = 行内竖排
    val singleColumnProgress = animateFloatAsState(
        targetValue = if (singleColumn) 1f else 0f,
        animationSpec = tween(300),
        label = "singleColumnProgress",
    )
    // 用布尔标记：直接读进度会让整屏每帧重组
    var singleColumnAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(singleColumn) {
        singleColumnAnimating = true
        delay(300.milliseconds)
        singleColumnAnimating = false
    }
    val scrollBehavior = MiuixScrollBehavior()
    val groups = uiState.groups

    val modeHintRes = when (uiState.mode) {
        ProxyViewModel.MODE_DIRECT -> R.string.proxy_mode_direct_hint
        ProxyViewModel.MODE_GLOBAL -> R.string.proxy_mode_global_hint
        else -> null
    }

    val showPopup = remember { mutableStateOf(false) }
    val showSortPopup = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 展开状态上提屏幕级：节点行是顶层 lazy item，存 item 内会随其销毁丢失，故用可保存的 SnapshotStateList 统一持有
    val expandedGroups = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    // 展开/收起进度（per 组，1 = 完全展开）。收起先跑动画再从 expandedGroups 摘除，期间靠
    // retainedGroups 保住行 item——变成 disappearing item 就会脱离布局在原位淡出，与上移的下方内容穿插
    //
    // 注意这是普通 mutableMapOf：新增 key 本身不触发重组，toggleGroup 随后写入 expandedGroups
    // 才会让持有进度的节点行重新测量。
    val expandProgress = remember { mutableMapOf<String, MutableFloatState>() }
    val retainedGroups = remember { mutableStateListOf<String>() }
    val expandJobs = remember { mutableMapOf<String, Job>() }

    fun toggleGroup(name: String) {
        val progress = expandProgress.getOrPut(name) {
            mutableFloatStateOf(if (name in expandedGroups) 1f else 0f)
        }
        val expanding = name !in expandedGroups
        expandJobs[name]?.cancel()
        if (expanding) {
            retainedGroups.remove(name)
            expandedGroups.add(name)
        } else {
            expandedGroups.remove(name)
            if (name !in retainedGroups) retainedGroups.add(name)
        }
        expandJobs[name] = coroutineScope.launch {
            animate(
                initialValue = progress.floatValue,
                targetValue = if (expanding) 1f else 0f,
                animationSpec = tween(300),
            ) { value, _ -> progress.floatValue = value }
            // 被打断时不执行，清理交给接手的新动画
            if (!expanding) {
                retainedGroups.remove(name)
            }
        }
    }

    // 排序 + 分块上提缓存：content lambda 随 expandedGroups/testingNodes 等任一变化整体重跑，
    // 不缓存则每次重组对每个展开组重排几百节点。key 取 groups 而非 uiState，懒填充跳过收起组
    val rowsCache = remember(groups, sortOption) { HashMap<String, List<List<String>>>() }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.proxy_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (groups.isNotEmpty()) {
                            Box {
                                IconButton(
                                    onClick = { showSortPopup.value = true },
                                    holdDownState = showSortPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Sort,
                                        contentDescription = stringResource(R.string.proxy_sort_title),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }

                                WindowListPopup(
                                    show = showSortPopup.value,
                                    popupPositionProvider = MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showSortPopup.value = false },
                                ) {
                                    ListPopupColumn {
                                        val sortResIds = listOf(
                                            R.string.proxy_sort_default,
                                            R.string.proxy_sort_name,
                                            R.string.proxy_sort_delay,
                                        )
                                        val currentKey = sortOption / 2
                                        val isReverse = sortOption % 2 != 0
                                        val groupSize = sortResIds.size + 1

                                        sortResIds.forEachIndexed { index, resId ->
                                            DropdownImpl(
                                                text = stringResource(resId),
                                                optionSize = groupSize,
                                                isSelected = currentKey == index,
                                                index = index,
                                                onSelectedIndexChange = {
                                                    viewModel?.updateSortOption(
                                                        index * 2 + if (isReverse) 1 else 0
                                                    )
                                                    showSortPopup.value = false
                                                },
                                            )
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier
                                                .padding(horizontal = 20.dp, vertical = 4.dp),
                                            thickness = 1.5.dp,
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_sort_reverse),
                                            optionSize = groupSize,
                                            isSelected = isReverse,
                                            index = sortResIds.size,
                                            onSelectedIndexChange = {
                                                viewModel?.updateSortOption(
                                                    currentKey * 2 + if (!isReverse) 1 else 0
                                                )
                                                showSortPopup.value = false
                                            },
                                        )
                                    }
                                }
                            }

                            Box {
                                IconButton(
                                    onClick = { showPopup.value = true },
                                    holdDownState = showPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.MoreCircle,
                                        contentDescription = stringResource(R.string.common_more),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }

                                WindowListPopup(
                                    show = showPopup.value,
                                    popupPositionProvider = MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showPopup.value = false },
                                ) {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_single_column),
                                            optionSize = 1,
                                            isSelected = singleColumn,
                                            index = 0,
                                            onSelectedIndexChange = {
                                                viewModel?.updateSingleColumn(!singleColumn)
                                                showPopup.value = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) { sidePadding ->
            if (groups.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.proxy_no_groups),
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.proxy_start_first),
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = bottomPadding,
                        start = sidePadding,
                        end = sidePadding,
                    ),
                ) {
                    if (modeHintRes != null) {
                        item(key = "mode_hint", contentType = "proxy_mode_hint") {
                            CardSegment(
                                isFirst = true,
                                isLast = true,
                                modifier = Modifier.animateItem(placementSpec = null),
                                outerTopPadding = 12.dp,
                                insidePadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(modeHintRes),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }

                    // 每组展平为「组头段 + 每行节点段」独立 lazy item；展开时只组合可见节点行，避免一次性组合整组造成卡顿
                    groups.forEach { group ->
                        val isExpanded = group.name in expandedGroups
                        // 收起动画未跑完的组仍产出行 item，靠行高收缩表达收起
                        val rowsPresent = isExpanded || group.name in retainedGroups
                        // 恒按 2 个分行：行数与 key 不随单列开关变化，morph 全在行内部
                        val rows = if (rowsPresent) {
                            rowsCache.getOrPut(group.name) {
                                sortNodes(group.all, group.delays, sortOption).chunked(2)
                            }
                        } else {
                            emptyList()
                        }

                        item(key = "group:${group.name}", contentType = "proxy_group_header") {
                            // 按目标态动画才能与行高收缩同步；跟 rowsPresent 会滞后整段收起时长
                            val headerBottomCorner by animateDpAsState(
                                targetValue = if (isExpanded) 0.dp else 16.dp,
                                animationSpec = tween(300),
                                label = "groupHeaderBottomCorner",
                            )
                            CardSegment(
                                isFirst = true,
                                isLast = !isExpanded,
                                modifier = Modifier.animateItem(placementSpec = null),
                                bottomCornerRadius = headerBottomCorner,
                                outerTopPadding = 12.dp,
                            ) {
                                ProxyGroupHeader(
                                    group = group,
                                    isExpanded = isExpanded,
                                    isTesting = group.name in uiState.testingGroups,
                                    onTestDelay = { viewModel?.testGroupDelay(group.name) },
                                    onUnfix = { viewModel?.unfixProxy(group.name) },
                                    onToggle = { toggleGroup(group.name) },
                                )
                            }
                        }

                        if (rows.isNotEmpty()) {
                            val lastRowIndex = rows.lastIndex
                            val groupExpandProgress = expandProgress[group.name]
                            rows.forEachIndexed { rowIndex, row ->
                                item(
                                    key = "nodes:${group.name}:$rowIndex",
                                    contentType = "proxy_node_row",
                                ) {
                                    CardSegment(
                                        isFirst = false,
                                        isLast = rowIndex == lastRowIndex,
                                        modifier = Modifier
                                            .animateItem(placementSpec = null)
                                            .clipToBounds(),
                                        // 底距交给行内 Layout 一起收缩，否则收完残留一条 12dp 底色
                                        insidePadding = PaddingValues(
                                            start = 12.dp,
                                            end = 12.dp,
                                        ),
                                    ) {
                                        ProxyNodeRow(
                                            row = row,
                                            group = group,
                                            singleColumnProgress = singleColumnProgress,
                                            expandProgress = groupExpandProgress,
                                            rowIndex = rowIndex,
                                            testingNodes = uiState.testingNodes,
                                            onTestNodeDelay = { nodeName ->
                                                viewModel?.testNodeDelay(nodeName)
                                            },
                                            onSelect = { proxyName ->
                                                viewModel?.selectProxy(group.name, proxyName)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(Modifier.padding(bottom = 12.dp))
                    }
                }
            }
        }
    }
}

/**
 * 把 [holdDown] 映射成 [interactionSource] 上的按下态（miuix 的 `HoldDownObserver` 是 internal）。
 * `interactions` 无 replay，故两处不能漏：离开组合补 Release，indication 换实例（深浅色切换）重发。
 */
@Composable
private fun HoldDownEffect(holdDown: Boolean, interactionSource: MutableInteractionSource) {
    val current = remember { mutableStateOf<HoldDownInteraction.HoldDown?>(null) }
    val indication = LocalIndication.current
    LaunchedEffect(holdDown, interactionSource, indication) {
        current.value?.let {
            interactionSource.emit(HoldDownInteraction.Release(it))
            current.value = null
        }
        if (holdDown) {
            val interaction = HoldDownInteraction.HoldDown()
            current.value = interaction
            interactionSource.emit(interaction)
        }
    }
    DisposableEffect(interactionSource) {
        onDispose {
            current.value?.let { interactionSource.tryEmit(HoldDownInteraction.Release(it)) }
            current.value = null
        }
    }
}

@Composable
private fun ProxyGroupHeader(
    group: ProxyGroupUi,
    isExpanded: Boolean,
    isTesting: Boolean,
    onTestDelay: () -> Unit,
    onUnfix: () -> Unit,
    onToggle: () -> Unit,
) {
    // 保持 State：解包后喂给 Modifier.rotate 会让整个组头在 300ms 内逐帧重组
    val rotation = animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(300),
        label = "groupHeaderArrow",
    )

    val interactionSource = remember { MutableInteractionSource() }
    HoldDownEffect(isExpanded, interactionSource)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧图标
        GroupIcon(
            icon = group.icon,
            name = group.name,
        )

        Spacer(Modifier.width(12.dp))

        // 中间：组名 + 当前节点
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.now.isNotEmpty()) {
                Text(
                    text = group.now,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 右侧：当前节点延迟 + 节点数 + 箭头
        val nowDelay = group.delays[group.now]
        if (nowDelay != null) {
            val timeoutText = stringResource(R.string.proxy_timeout)
            val delayText = if (nowDelay < 0) timeoutText else "${nowDelay}ms"
            Text(
                text = delayText,
                fontSize = 12.sp,
                color = StatusColors.delay(nowDelay),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "${group.all.size}",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        // 固定是组级属性，放组头才能在收起状态下操作
        if (group.isFixed) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onUnfix,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Unpin,
                    contentDescription = stringResource(R.string.proxy_unfix),
                    modifier = Modifier.size(16.dp),
                    tint = StatusColors.warning,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onTestDelay,
            enabled = !isTesting,
            modifier = Modifier.size(24.dp),
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    size = 14.dp,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.proxy_test_group_delay),
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val layoutDirection = LocalLayoutDirection.current
        Image(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier
                .size(width = 10.dp, height = 16.dp)
                .graphicsLayer {
                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                    rotationZ = rotation.value
                },
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
        )
    }
}

@Composable
private fun GroupIcon(
    icon: String,
    name: String,
) {
    if (icon.isNotEmpty()) {
        val iconVersion by IconLoader.iconVersion.collectAsStateWithLifecycle()
        var bitmap by remember(icon) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(icon, iconVersion) {
            if (bitmap == null) {
                bitmap = IconLoader.loadIcon(icon)
            }
        }
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = name,
                modifier = Modifier
                    .size(36.dp)
                    .squircleClip(8.dp),
            )
        } else {
            DefaultGroupIcon(name)
        }
    } else {
        DefaultGroupIcon(name)
    }
}

@Composable
private fun DefaultGroupIcon(name: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .squircleBackground(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (name.isEmpty()) "" else {
                val first = name[0]
                if (first.isHighSurrogate() && name.length > 1 && name[1].isLowSurrogate()) {
                    name.substring(0, 2)
                } else {
                    first.toString()
                }
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary.copy(0.8f),
        )
    }
}

// 竖排间距对齐段间 bottom padding，让单列态与两列态视觉一致
private val NodeRowHorizontalGap = 8.dp
private val NodeRowVerticalGap = 12.dp
private val NodeRowBottomPadding = 12.dp

// 首行与组头之间的间距。和底距一样交给行内 Layout 而非 CardSegment.insidePadding：
// 段上的 padding 不参与行高收缩，收起完成后会残留一条顶部底色
private val NodeRowTopPadding = 12.dp

// 一行恒定 ≤2 个节点，是节点网格的独立 lazy item 单元；排序/分行在 LazyColumn 内容 lambda 完成。
// 单列切换不改分行而由本行 Layout 插值——两个节点同处一个 layout scope 才能连续过渡。
@Composable
private fun ProxyNodeRow(
    row: List<String>,
    group: ProxyGroupUi,
    singleColumnProgress: State<Float>,
    expandProgress: FloatState?,
    rowIndex: Int,
    testingNodes: ImmutableSet<String> = persistentSetOf(),
    onTestNodeDelay: (String) -> Unit = {},
    onSelect: (String) -> Unit,
) {
    Layout(
        content = {
            row.forEach { proxyName ->
                val isSelected = proxyName == group.now
                val delay = group.delays[proxyName]
                val nodeType = group.nodeTypes[proxyName] ?: ""
                val isSelectable = group.isSelectable

                ProxyNodeCard(
                    name = proxyName,
                    type = nodeType,
                    delay = delay,
                    isSelected = isSelected,
                    isFixed = proxyName == group.fixed,
                    isSelectable = isSelectable,
                    isTesting = proxyName in testingNodes,
                    onTestDelay = { onTestNodeDelay(proxyName) },
                    onClick = { onSelect(proxyName) },
                )
            }
        },
    ) { measurables, constraints ->
        // 两个进度都在 measure 阶段读：动画帧只重测量本行，不重组节点卡片
        val fraction = singleColumnProgress.value
        val rowWidth = constraints.maxWidth
        val horizontalGap = NodeRowHorizontalGap.roundToPx()
        val verticalGap = NodeRowVerticalGap.roundToPx()
        val halfWidth = (rowWidth - horizontalGap) / 2
        val cellWidth = lerp(halfWidth, rowWidth, fraction)

        val placeables = measurables.map {
            it.measure(constraints.copy(minWidth = cellWidth, maxWidth = cellWidth))
        }
        val first = placeables.firstOrNull()
        val second = placeables.getOrNull(1)

        // 竖排基准取首项实际高度：两节点高度不等时（有无协议 Badge）才不留多余空隙
        val secondX = lerp(halfWidth + horizontalGap, 0, fraction)
        val secondY = lerp(0, (first?.height ?: 0) + verticalGap, fraction)
        val topPadding = if (rowIndex == 0) NodeRowTopPadding.roundToPx() else 0
        val contentHeight = topPadding + maxOf(
            first?.height ?: 0,
            if (second != null) secondY + second.height else 0,
        ) + NodeRowBottomPadding.roundToPx()

        val expand = expandProgress?.floatValue ?: 1f
        val visibleFraction = expand
        val rowHeight = (contentHeight * visibleFraction).roundToInt()

        // 内容按完整高度测量并顶部对齐，超出由段上 clipToBounds 裁掉——卷起而非压扁变形
        layout(rowWidth, rowHeight) {
            first?.placeRelative(0, topPadding)
            second?.placeRelative(secondX, secondY + topPadding)
        }
    }
}

@Composable
private fun ProxyNodeCard(
    name: String,
    type: String,
    delay: Int?,
    isSelected: Boolean,
    isFixed: Boolean,
    isSelectable: Boolean,
    isTesting: Boolean = false,
    onTestDelay: () -> Unit = {},
    onClick: () -> Unit,
) {
    val timeoutStr = stringResource(R.string.proxy_timeout)
    val delayText = when {
        delay == null -> null
        delay < 0 -> timeoutStr
        else -> "$delay"
    }
    val delayColor = StatusColors.delay(delay)
    val testNodeDelayLabel = stringResource(R.string.proxy_test_node_delay)

    // 固定与选中异色：同色就看不出该组已不再自动切换
    val backgroundColor = when {
        isFixed -> StatusColors.fixedNodeContainer
        isSelected -> StatusColors.selectedNodeContainer
        else -> MiuixTheme.colorScheme.surface
    }

    Box(
        modifier = Modifier
            .then(
                if (isSelectable) {
                    Modifier
                        .squircleSurface(color = backgroundColor, cornerRadius = 12.dp)
                        .clickable(onClick = onClick)
                } else {
                    Modifier.squircleBackground(color = backgroundColor, cornerRadius = 12.dp)
                }
            )
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Column {
            // 第一行：节点名 + 延迟区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 包成子 Row：外层 SpaceBetween 会把剩余空间摄进图钉与名字的间隙
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isFixed) {
                        Icon(
                            imageVector = MiuixIcons.Pin,
                            contentDescription = stringResource(R.string.proxy_fixed),
                            modifier = Modifier.size(11.dp),
                            tint = StatusColors.warning,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    // 节点名不截断：超出可用宽度时走跑马灯滚动，短名保持原宽（fill = false）
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .basicMarquee(iterations = Int.MAX_VALUE),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 28.dp)
                        .clickable(
                            enabled = !isTesting,
                            interactionSource = null,
                            indication = null,
                            onClickLabel = testNodeDelayLabel,
                        ) { onTestDelay() },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // 测试中 / 已测显示延迟 / 未测显示刷新图标，三态互斥
                    // 行高由左侧节点名（13.sp）主导，切换不抖动
                    when {
                        isTesting -> CircularProgressIndicator(
                            size = 12.dp,
                            strokeWidth = 2.dp,
                        )

                        delayText != null -> Text(
                            text = delayText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = delayColor,
                        )

                        else -> Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = testNodeDelayLabel,
                            modifier = Modifier.size(14.dp),
                            tint = StatusColors.neutral,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第二行：协议类型 Badge
            if (type.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), 3.dp)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// 节点排序。sortOption 的编码见 ProxyViewModel._sortOption。
// 延迟排序时超时 (-1) 与未测 (null) 永远沉底，倒序也只翻转已测部分
private fun sortNodes(
    names: List<String>,
    delays: Map<String, Int>,
    sortOption: Int,
): List<String> {
    val key = sortOption / 2
    val reverse = sortOption % 2 != 0
    return when (key) {
        1 -> {
            val sorted = names.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            if (reverse) sorted.reversed() else sorted
        }

        2 -> {
            val (valid, invalid) = names.partition {
                val d = delays[it]
                d != null && d > 0
            }
            val sortedValid = valid.sortedBy { delays[it] ?: Int.MAX_VALUE }
            val finalValid = if (reverse) sortedValid.reversed() else sortedValid
            finalValid + invalid
        }

        else -> if (reverse) names.reversed() else names
    }
}
