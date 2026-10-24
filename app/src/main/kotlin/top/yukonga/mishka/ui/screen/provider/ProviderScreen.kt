package top.yukonga.mishka.ui.screen.provider

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.custom.forms.FileContentDialog
import top.yukonga.mishka.custom.forms.MAX_EDIT_BYTES
import top.yukonga.mishka.custom.forms.MAX_UPLOAD_BYTES
import top.yukonga.mishka.custom.forms.readBytesLimited
import top.yukonga.mishka.custom.forms.resolveProviderFile
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.service.RootHelper
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.util.formatIsoTimeAsLocalShort
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.ProviderErrorKey
import top.yukonga.mishka.viewmodel.ProviderItemUi
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private data class ProviderFileTarget(
    val name: String,
    val path: String,
    val format: String?,
    val profileFile: File,
    val runtimeFile: File,
)

private fun resolveProviderActionFile(baseDir: String, cfgPath: String): File? {
    val trimmed = cfgPath.trim()
    val resolved = resolveProviderFile(baseDir, trimmed) ?: return null
    val canonical = runCatching { resolved.canonicalFile }.getOrNull() ?: return null
    if (!trimmed.startsWith("/")) {
        val root = runCatching { File(baseDir).canonicalFile.path }.getOrNull() ?: return null
        if (canonical.path != root && !canonical.path.startsWith(root + File.separator)) return null
    }
    return canonical
}

private suspend fun resolveProviderFileTarget(
    viewModel: SubscriptionViewModel?,
    name: String,
    subscriptionId: String?,
    useRootRuntime: Boolean,
): ProviderFileTarget? = withContext(Dispatchers.IO) {
    val source = viewModel?.readProxyProviderFileSource(name, subscriptionId, useRootRuntime)
        ?: return@withContext null
    val profileFile = resolveProviderActionFile(source.profileWorkDir, source.path)
        ?: return@withContext null
    val runtimeFile = resolveProviderActionFile(source.runtimeWorkDir, source.path)
        ?: return@withContext null
    ProviderFileTarget(name, source.path, source.format, profileFile, runtimeFile)
}

private suspend fun syncProviderFileToRuntime(target: ProviderFileTarget): Boolean = withContext(Dispatchers.IO) {
    val sameFile = runCatching { target.profileFile.canonicalPath == target.runtimeFile.canonicalPath }.getOrDefault(false)
    if (sameFile) return@withContext true
    RootHelper.copyFileAsRoot(target.profileFile.absolutePath, target.runtimeFile.absolutePath)
}

private const val PROVIDER_FILE_TEMPLATE =
    "proxies:\n  # - { name: node1, type: ss, server: example.com, port: 8388, cipher: aes-256-gcm, password: xxx }\n"

@Composable
fun ProviderScreen(
    viewModel: ProviderViewModel,
    homeViewModel: HomeViewModel? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val homeState = homeViewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: HomeUiState()
    val subscriptionState = subscriptionViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val activeSubscriptionId = subscriptionState?.subscriptions?.firstOrNull { it.isActive }?.id
    val useRootRuntime = homeState.tunMode != TunMode.Vpn
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun onProviderFileChanged(target: ProviderFileTarget, successMessage: String) {
        scope.launch {
            val synced = syncProviderFileToRuntime(target)
            if (!synced) {
                showToast("$successMessage；ROOT runtime 同步失败，正在重启以从订阅目录重新复制", long = true)
                if (homeViewModel != null) homeViewModel.restartProxy()
                else showToast("请手动重启代理以同步 ROOT runtime", long = true)
                return@launch
            }
            showToast(successMessage)
            viewModel.updateProvider(target.name, isRuleProvider = false)
        }
    }

    var fileActionResolving by remember { mutableStateOf<String?>(null) }
    var editingFileTarget by remember { mutableStateOf<ProviderFileTarget?>(null) }
    var uploadFileTarget by remember { mutableStateOf<ProviderFileTarget?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = uploadFileTarget
        uploadFileTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        readBytesLimited(it, MAX_UPLOAD_BYTES + 1)
                    } ?: error("读取所选文件失败")
                    require(bytes.size.toLong() <= MAX_UPLOAD_BYTES) { "文件过大（>8MB）" }
                    target.profileFile.parentFile?.mkdirs()
                    target.profileFile.writeBytes(bytes)
                    true
                }
            }
            result.fold(
                onSuccess = { onProviderFileChanged(target, "已上传文件") },
                onFailure = { showToast("上传失败：${it.message ?: it::class.simpleName}", long = true) },
            )
        }
    }

    fun requestProviderFileAction(name: String, edit: Boolean) {
        if (fileActionResolving != null) return
        if (subscriptionViewModel == null) {
            showToast("无法读取活动配置，请重新打开主页工具", long = true)
            return
        }
        fileActionResolving = name
        scope.launch {
            val target = resolveProviderFileTarget(
                subscriptionViewModel,
                name,
                activeSubscriptionId,
                useRootRuntime,
            )
            fileActionResolving = null
            if (target == null) {
                showToast("无法从活动配置解析 file 提供者路径，请检查 proxy-providers.$name.path", long = true)
                return@launch
            }
            if (edit) {
                when {
                    target.format.equals("mrs", ignoreCase = true) || target.profileFile.name.endsWith(".mrs", ignoreCase = true) ->
                        showToast("mrs 是二进制格式，不支持在线编辑，请用上传文件替换", long = true)
                    target.profileFile.exists() && !target.profileFile.isFile ->
                        showToast("提供者路径不是普通文件：${target.profileFile.path}", long = true)
                    target.profileFile.exists() && target.profileFile.length() > MAX_EDIT_BYTES ->
                        showToast("文件超过 4MB，请直接用文件管理器编辑", long = true)
                    else -> editingFileTarget = target
                }
            } else {
                uploadFileTarget = target
                uploadLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    var selectedTabIndex by rememberSaveable("provider_type_tab") { mutableIntStateOf(RULE_PROVIDERS_TAB) }
    val selectedIsRuleProvider = selectedTabIndex == RULE_PROVIDERS_TAB
    val visibleProviders = uiState.providers.filter { it.isRuleProvider == selectedIsRuleProvider }
    // 非当前 Tab 的更新错误没有行内 banner 可见，聚合到列表顶部的汇总卡展示
    val otherTabErrors = uiState.providerErrors.entries
        .filter { it.key.isRuleProvider != selectedIsRuleProvider }
        .sortedBy { it.key.name }
    val providerTabs = listOf(
        stringResource(R.string.provider_rule_providers),
        stringResource(R.string.provider_proxy_providers),
    )
    val providerTabsContent: @Composable () -> Unit = {
        TabRow(
            tabs = providerTabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        )
    }
    val otherTabErrorsContent: @Composable () -> Unit = {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                otherTabErrors.forEach { (errorKey, message) ->
                    Text(
                        text = stringResource(R.string.provider_update_failed, errorKey.name, message),
                        fontSize = 12.sp,
                        color = StatusColors.danger,
                    )
                }
            }
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.provider_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.updateAll() }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.subscription_update_all),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
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
            ),
        ) {
            if (visibleProviders.isEmpty() && !uiState.isLoading) {
                item(key = "empty", contentType = "empty") {
                    Column(
                        modifier = Modifier.fillParentMaxSize(),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        providerTabsContent()
                        if (otherTabErrors.isNotEmpty()) {
                            otherTabErrorsContent()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.provider_no_providers),
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                // 仅当两个 Tab 都没有 provider 时才提示启动服务；
                                // 当前 Tab 为空但另一 Tab 有 provider 说明服务已在运行
                                if (uiState.providers.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.provider_start_first),
                                        modifier = Modifier.padding(top = 6.dp),
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "top_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "provider_type_tabs", contentType = "tabs") {
                    providerTabsContent()
                }
                if (otherTabErrors.isNotEmpty()) {
                    item(key = "other_tab_errors", contentType = "error_summary") {
                        otherTabErrorsContent()
                    }
                }
                items(
                    items = visibleProviders,
                    key = { "${it.isRuleProvider}:${it.name}" },
                    contentType = { "provider" },
                ) { provider ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ProviderItem(
                            provider = provider,
                            error = uiState.providerErrors[
                                ProviderErrorKey(provider.name, provider.isRuleProvider)
                            ],
                            fileActionEnabled = fileActionResolving == null,
                            onUpdate = {
                                viewModel.updateProvider(provider.name, provider.isRuleProvider)
                            },
                            onEditFile = { requestProviderFileAction(provider.name, edit = true) },
                            onUploadFile = { requestProviderFileAction(provider.name, edit = false) },
                        )
                    }
                }
            }
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }

    ProviderRefreshDialog(
        show = uiState.refresh != null,
        progress = uiState.refresh,
    )

    editingFileTarget?.let { target ->
        FileContentDialog(
            title = "编辑代理提供者文件",
            file = target.profileFile,
            baseDir = target.profileFile.parentFile?.absolutePath,
            cfgPath = target.path,
            newFileText = PROVIDER_FILE_TEMPLATE,
            wrapProxies = true,
            scope = scope,
            onDismiss = { editingFileTarget = null },
            onSaved = {
                editingFileTarget = null
                onProviderFileChanged(target, "已保存提供者文件")
            },
        )
    }
}

private const val RULE_PROVIDERS_TAB = 0

@Composable
private fun ProviderItem(
    provider: ProviderItemUi,
    error: String?,
    fileActionEnabled: Boolean,
    onUpdate: () -> Unit,
    onEditFile: () -> Unit,
    onUploadFile: () -> Unit,
) {
    Column {
        BasicComponent(
            title = provider.name,
            summary = stringResource(
                if (provider.isRuleProvider) R.string.provider_kind_rule else R.string.provider_kind_proxy,
                provider.type.lowercase(),
            ),
            endActions = {
                val isFileProxyProvider = !provider.isRuleProvider && provider.type.equals("file", ignoreCase = true)
                if (isFileProxyProvider) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.common_edit),
                            minWidth = 0.dp,
                            enabled = fileActionEnabled,
                            onClick = onEditFile,
                        )
                        TextButton(
                            text = stringResource(R.string.provider_upload_file),
                            minWidth = 0.dp,
                            enabled = fileActionEnabled,
                            onClick = onUploadFile,
                        )
                    }
                } else if (!provider.vehicleType.equals("Inline", ignoreCase = true)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = formatIsoTimeAsLocalShort(provider.updatedAt),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Image(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onUpdate,
                                ),
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.provider_update),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                        )
                    }
                }
            },
        )
        // 退出动画期间 error 已置 null，保留最后一条非空错误供动画帧渲染，
        // 避免闪现空原因文案并触发 liveRegion 播报
        var lastError by remember { mutableStateOf(error) }
        if (error != null) lastError = error
        AnimatedVisibility(
            visible = error != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text = stringResource(R.string.provider_update_failed, provider.name, lastError.orEmpty()),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                fontSize = 12.sp,
                color = StatusColors.danger,
            )
        }
    }
}
