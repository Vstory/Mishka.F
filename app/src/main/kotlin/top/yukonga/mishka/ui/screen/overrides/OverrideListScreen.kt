package top.yukonga.mishka.ui.screen.overrides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.HintCard
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.util.formatEpochMillisAsLocal
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun OverrideListScreen(
    viewModel: OverrideProfileViewModel,
    onBack: () -> Unit = {},
    onAdd: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onEditFile: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ClearOverrideErrorOnExit(viewModel)
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_overrides),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { OverrideBackButton(onBack) },
                    actions = {
                        if (state.profiles.any { it.isRemote }) {
                            IconButton(onClick = viewModel::updateAll, enabled = !state.isLoading) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = stringResource(R.string.subscription_update_all),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.clearError(); onAdd() },
                            enabled = !state.isLoading,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = stringResource(R.string.override_add),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
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

                if (state.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(text = state.error, color = StatusColors.danger)
                        }
                    }
                }

                if (state.profiles.isEmpty()) {
                    item(key = "empty") {
                        HintCard(text = stringResource(R.string.override_empty))
                    }
                }

                items(state.profiles, key = { it.id }) { profile ->
                    OverrideItem(
                        profile = profile,
                        isLoading = state.isLoading,
                        onOpen = { viewModel.clearError(); onEditFile(profile.id) },
                        onEdit = { viewModel.clearError(); onEdit(profile.id) },
                        onUpdate = { viewModel.update(profile.id) },
                        onDelete = { viewModel.delete(profile.id) },
                        modifier = Modifier.animateItem(),
                    )
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
private fun OverrideItem(
    profile: OverrideProfile,
    isLoading: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = { if (!isLoading) onOpen() },
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Text(
            text = profile.name,
            fontSize = 17.sp,
            fontWeight = FontWeight(550),
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = overrideSummary(profile),
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (profile.sourceLocation.isNotBlank()) {
                    Text(
                        text = profile.sourceLocation,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                profile.lastUpdatedAt?.let {
                    Text(
                        text = stringResource(
                            R.string.subscription_updated_at,
                            formatEpochMillisAsLocal(it),
                        ),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            ItemAction(
                icon = MiuixIcons.MoreCircle,
                contentDescription = stringResource(R.string.common_edit),
                enabled = !isLoading,
                onClick = onEdit,
            )
            if (profile.isRemote) {
                Spacer(Modifier.width(8.dp))
                ItemAction(
                    icon = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.common_update),
                    enabled = !isLoading,
                    onClick = onUpdate,
                )
            }
            Spacer(Modifier.width(8.dp))
            ItemAction(
                icon = MiuixIcons.Delete,
                contentDescription = stringResource(R.string.common_delete),
                enabled = !isLoading,
                onClick = { showDeleteDialog = true },
            )
        }
    }

    WindowDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.override_delete_title),
        summary = stringResource(R.string.override_delete_summary, profile.name),
        onDismissRequest = { showDeleteDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showDeleteDialog = false },
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showDeleteDialog = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun ItemAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        )
    }
}
