package top.yukonga.mishka.ui.screen.overrides

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.custom.forms.DragHandle
import top.yukonga.mishka.custom.forms.DragSortRow
import top.yukonga.mishka.custom.forms.DragSortState
import top.yukonga.mishka.custom.forms.dragSortItem
import top.yukonga.mishka.custom.forms.rememberDragSortState
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.domain.model.orderedOverrideIds
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.HintCard
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SubscriptionOverridesScreen(
    subscriptionId: String,
    subscriptionViewModel: SubscriptionViewModel,
    overrideViewModel: OverrideProfileViewModel,
    onBack: () -> Unit = {},
) {
    val subscriptionState by subscriptionViewModel.uiState.collectAsStateWithLifecycle()
    val overrideState by overrideViewModel.uiState.collectAsStateWithLifecycle()
    val subscription = subscriptionState.subscriptions.find { it.id == subscriptionId }
    if (subscription == null) {
        LaunchedEffect(subscriptionId) { onBack() }
        return
    }
    LaunchedEffect(subscriptionId) { overrideViewModel.clearError() }
    ClearOverrideErrorOnExit(overrideViewModel)

    val profiles = overrideState.profiles
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    val initialOrder = remember(profiles, subscription.overrideSortPreference) {
        val rank = subscription.overrideSortPreference.withIndex().associate { it.value to it.index }
        profiles.sortedWith(compareBy<OverrideProfile> { rank[it.id] ?: Int.MAX_VALUE }.thenBy { it.name })
            .map { it.id }
    }
    val initialSelected = remember(profiles, subscription.orderedOverrideIds) {
        subscription.orderedOverrideIds.filter { it in profilesById }
    }
    var order by rememberSaveable(subscriptionId) { mutableStateOf(initialOrder) }
    var selected by rememberSaveable(subscriptionId) { mutableStateOf(initialSelected) }
    val displayed = order.filter { it in profilesById } + initialOrder.filter { it !in order }
    // 列表就是「覆写生效顺序」本身：按住行尾把手拖动即改顺序，不再用一对上/下箭头一点点挪。
    // 拖动只改 order 的顺序，落点判定与校验仍走原有保存路径。
    val listState = rememberLazyListState()
    val dragSort = rememberDragSortState(displayed.size) { from, to ->
        val moved = displayed.toMutableList().also { it.add(to, it.removeAt(from)) }
        order = moved + order.filter { it !in moved }
    }
    val selectedIds = displayed.filter { it in selected }
    val hasChanges = displayed != initialOrder || selectedIds != initialSelected

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.subscription_overrides),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { OverrideBackButton(onBack) },
                    actions = {
                        val canSave = hasChanges && !overrideState.isLoading
                        IconButton(
                            enabled = canSave,
                            onClick = {
                                overrideViewModel.setSelection(subscriptionId, selectedIds, displayed, onBack)
                            },
                        ) {
                            if (overrideState.isLoading) {
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = MiuixIcons.Ok,
                                    contentDescription = stringResource(R.string.common_save),
                                    tint = if (canSave) MiuixTheme.colorScheme.onSurface
                                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                state = listState,
                // 拖动一开始就关掉用户滚动：外层 scrollable 退出仲裁；自动翻滚走 dispatchRawDelta 不受影响
                userScrollEnabled = dragSort.dragging < 0,
                modifier = Modifier
                    .fillMaxSize()
                    .then(dragSort.containerModifier { listState.dispatchRawDelta(it) })
                    .horizontalCutoutPadding()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = sidePadding,
                    end = sidePadding,
                ),
            ) {
                item(key = "top_spacer") { Spacer(Modifier.height(12.dp)) }

                if (overrideState.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(text = overrideState.error, color = StatusColors.danger)
                        }
                    }
                }

                if (displayed.isEmpty()) {
                    item(key = "empty") {
                        HintCard(text = stringResource(R.string.override_empty))
                    }
                }

                // 顺序由 DragSortState.order 定（拖动中实时换位）；key 仍是覆写 id，内容按原始下标取
                items(dragSort.order, key = { displayed.getOrNull(it) ?: it }) { index ->
                    val id = displayed.getOrNull(index)
                    val profile = id?.let { profilesById[it] }
                    if (id != null && profile != null) {
                        val checked = id in selected
                        DragSortRow(state = dragSort, index = index, modifier = dragSortItem(dragSort, index)) {
                            OverrideSelectionItem(
                                profile = profile,
                                checked = checked,
                                dragSort = dragSort,
                                index = index,
                                onToggle = {
                                    selected = if (checked) selected - id else selected + id
                                },
                            )
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }
}

@Composable
private fun OverrideSelectionItem(
    profile: OverrideProfile,
    checked: Boolean,
    dragSort: DragSortState,
    index: Int,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = profile.name,
            summary = overrideSummary(profile),
            startAction = {
                Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = onToggle,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            // 排序方式与 mihomo_box 面板一致：行尾按住把手拖动，不再用一对上/下箭头
            endActions = { DragHandle(dragSort, index) },
            onClick = onToggle,
        )
    }
}
