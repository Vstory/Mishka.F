package top.yukonga.mishka.custom.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.util.sheetContentSafePadding
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 面板选择/管理的内容（挂在 [top.yukonga.miuix.kmp.window.WindowBottomSheet] 里）。
 *
 * 三个内置项：本地（控制器 `/ui`）、Zashboard、MetaCubeXD，其后接用户自定义项。
 * 自定义项可删；内置项不可——它们的存在由代码决定，删了下次进来又会长回来。
 *
 * @param onSelect 选中面板；切换由 [PanelScreen] 负责重建 WebView 会话
 */
@Composable
fun PanelSheetContent(
    panels: List<PanelEntry>,
    selectedId: String,
    onSelect: (PanelEntry) -> Unit,
    onDelete: (PanelEntry) -> Unit,
    onAdd: (name: String, url: String) -> Boolean,
) {
    val nameState = rememberTextFieldState()
    val urlState = rememberTextFieldState()
    var addError by remember { mutableStateOf<String?>(null) }

    val addTitle = stringResource(R.string.panel_sheet_add_title)
    val nameLabel = stringResource(R.string.panel_field_name)
    val urlLabel = stringResource(R.string.panel_field_url)
    val nameRequired = stringResource(R.string.panel_error_name_required)
    val urlInvalid = stringResource(R.string.panel_error_url_invalid)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .sheetContentSafePadding(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
        ) {
            panels.forEachIndexed { index, panel ->
                PanelRow(
                    panel = panel,
                    selected = panel.id == selectedId,
                    onSelect = { onSelect(panel) },
                    onDelete = { onDelete(panel) },
                )
                if (index != panels.lastIndex) {
                    HorizontalDivider()
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = addTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            TextField(
                state = nameState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                label = nameLabel,
            )
            TextField(
                state = urlState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                label = urlLabel,
            )
            val addErrorMessage = addError
            if (addErrorMessage != null) {
                Text(
                    text = addErrorMessage,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = StatusColors.danger,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.panel_add),
                    minWidth = 0.dp,
                    minHeight = 36.dp,
                    onClick = {
                        val name = nameState.text.trim().toString()
                        val url = urlState.text.trim().toString()
                        when {
                            name.isBlank() -> addError = nameRequired
                            !url.startsWith("http://", ignoreCase = true) &&
                                !url.startsWith("https://", ignoreCase = true) -> addError = urlInvalid
                            else -> {
                                addError = null
                                if (onAdd(name, url)) {
                                    nameState.setTextAndPlaceCursorAtEnd("")
                                    urlState.setTextAndPlaceCursorAtEnd("")
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PanelRow(
    panel: PanelEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    BasicComponent(
        title = panel.name,
        // 本地面板没有编译期地址，副标题直接说清它从哪来
        summary = panel.url ?: stringResource(R.string.panel_local_subtitle),
        onClick = onSelect,
        endActions = {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (!panel.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}