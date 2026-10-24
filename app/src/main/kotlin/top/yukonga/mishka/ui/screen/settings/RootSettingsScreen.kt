package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.domain.model.TunOverride
import top.yukonga.mishka.service.ConfigGenerator
import top.yukonga.mishka.service.SystemIpv6
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.ui.component.TetherInterfaceEditDialog
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.tetherInterfaceSummary
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * ROOT 模式专属设置页。仅在 ROOT 模式下可见。
 *
 * 内容：
 * - TUN 设备名称（mihomo 创建的 TUN 接口名，代理运行时不可改）
 * - 热点客户端流量处置（绕过代理 / 走代理）
 * - 热点接口名列表（手填 + 扫描辅助）
 * - 系统：系统 IPv6 开关（默认关闭，实时显示首选上网 APN 的 IPv6 协议状态）
 */
@Composable
fun RootSettingsScreen(
    storage: PlatformStorage,
    isProxyRunning: Boolean,
    userTunDeviceOverride: String? = null,
    userTunOverride: TunOverride? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    onJumboMtuChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val serviceController = remember(context) { ProxyServiceController(context) }
    val activeProfileId = storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "").takeIf { it.isNotEmpty() }
    var profileTunConfig by remember(activeProfileId) { mutableStateOf<TunOverride?>(null) }
    var profileTunConfigLoaded by remember(activeProfileId) { mutableStateOf(activeProfileId == null) }
    var preferUserTunOverrides by remember(activeProfileId) { mutableStateOf(false) }
    LaunchedEffect(activeProfileId, subscriptionViewModel) {
        profileTunConfigLoaded = false
        val (config, preferAppValues) = withContext(Dispatchers.IO) {
            val prefer = activeProfileId?.let { id ->
                subscriptionViewModel?.shouldPreferUserTunOverrides(id)
            } == true
            val runtimeValue = activeProfileId?.let { id ->
                subscriptionViewModel?.readProfileTunConfig(id)
            }
            (runtimeValue ?: ConfigGenerator.readSubscriptionTunConfig(context, activeProfileId)) to prefer
        }
        profileTunConfig = config
        preferUserTunOverrides = preferAppValues
        profileTunConfigLoaded = true
    }

    val profileTunDevice = profileTunConfig?.device?.trim()?.takeIf { it.isNotEmpty() }
    var tunDevice by remember {
        mutableStateOf(storage.getString(StorageKeys.ROOT_TUN_DEVICE, DEFAULT_TUN_DEVICE))
    }
    val appTunDeviceOverride = userTunDeviceOverride?.trim()?.takeIf { it.isNotEmpty() }
    val effectiveTunDevice = profileTunDevice ?: appTunDeviceOverride ?: tunDevice
    val canEditTunDevice = !isProxyRunning && profileTunConfigLoaded && profileTunDevice == null && appTunDeviceOverride == null
    var showTunDeviceDialog by remember { mutableStateOf(false) }
    val tunDeviceTextState = rememberTextFieldState()

    var jumboMtu by remember(activeProfileId) {
        mutableStateOf(storage.getString(StorageKeys.ROOT_TUN_JUMBO_MTU, "true") == "true")
    }
    LaunchedEffect(activeProfileId, profileTunConfig, profileTunConfigLoaded, preferUserTunOverrides, userTunOverride) {
        if (!profileTunConfigLoaded) return@LaunchedEffect
        val fallback = storage.getString(StorageKeys.ROOT_TUN_JUMBO_MTU, "true") == "true"
        val profileMtu = if (preferUserTunOverrides) {
            userTunOverride?.mtu ?: profileTunConfig?.mtu
        } else {
            profileTunConfig?.mtu ?: userTunOverride?.mtu
        }
        val profileGso = if (preferUserTunOverrides) {
            userTunOverride?.gso ?: profileTunConfig?.gso
        } else {
            profileTunConfig?.gso ?: userTunOverride?.gso
        }
        jumboMtu = if (profileMtu != null || profileGso != null) {
            (profileMtu?.let { it > 1500 } ?: fallback) && (profileGso ?: fallback)
        } else {
            fallback
        }
    }

    var allowIpv6 by remember {
        mutableStateOf(storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true")
    }

    // ---- 系统 IPv6（mihomo_box 逻辑移植，见 service/SystemIpv6.kt）----
    val hasRoot = storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
    val systemScope = rememberCoroutineScope()
    var systemIpv6 by remember { mutableStateOf(SystemIpv6.isEnabled(storage)) }
    var apnRows by remember { mutableStateOf<List<SystemIpv6.ApnRow>?>(null) }
    val ipv6OffText = stringResource(R.string.root_system_ipv6_summary_off)
    val ipv6OnText = stringResource(R.string.root_system_ipv6_summary_on)
    val ipv6NoRootText = stringResource(R.string.root_system_ipv6_no_root)
    val ipv6NoApnText = stringResource(R.string.root_system_ipv6_apn_none)
    val ipv6ApplyFailedText = stringResource(R.string.root_system_ipv6_apply_failed)
    // 打开设置页时读取一次首选 APN（root 查询）；开关切换成功后再读一次。离开页面不再查询。
    LaunchedEffect(hasRoot) {
        if (!hasRoot) return@LaunchedEffect
        apnRows = withContext(Dispatchers.IO) { SystemIpv6.readPreferApn(context) }
    }
    val preferredApn = apnRows?.firstOrNull { !it.isIms }
    val apnLine: String? = when {
        apnRows == null -> null
        preferredApn == null -> ipv6NoApnText
        else -> stringResource(
            R.string.root_system_ipv6_apn_line,
            preferredApn.apn.ifBlank { "-" },
            SystemIpv6.protocolLabel(preferredApn.protocol),
            SystemIpv6.protocolLabel(preferredApn.roaming),
        )
    }
    val systemIpv6Summary = if (!hasRoot) {
        ipv6NoRootText
    } else {
        listOfNotNull(
            if (systemIpv6) ipv6OnText else ipv6OffText,
            apnLine,
        ).joinToString("\n")
    }

    var forceReapply by remember {
        mutableStateOf(storage.getString(StorageKeys.ROOT_ATTACH_FORCE_REAPPLY, "false") == "true")
    }

    val bypassLabel = stringResource(R.string.root_tether_mode_bypass)
    val proxyLabel = stringResource(R.string.root_tether_mode_proxy)
    val tetherModeValues = listOf("bypass", "proxy")
    var tetherMode by remember { mutableStateOf(storage.getString(StorageKeys.ROOT_TETHER_MODE, "bypass")) }
    var tetherIfaces by remember {
        mutableStateOf(storage.getString(StorageKeys.ROOT_TETHER_IFACES, DEFAULT_TETHER_IFACES))
    }
    var showTetherDialog by remember { mutableStateOf(false) }

    // 读一次 submode：TPROXY / EBPF 下隐藏 tether mode 下拉，显示说明文案
    // （用户改 TUN_MODE 必须先停代理，不需要热更新）
    val tunModeValue = storage.getString(StorageKeys.TUN_MODE, "vpn")
    val isTproxy = tunModeValue == "root_tproxy"
    val isEbpf = tunModeValue == "root_ebpf"

    // xt_TPROXY 内核能力探测结果，仅在 TPROXY submode 或 PROXY 热点模式下显示告警
    val tproxyCapable = storage.getString(StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE, "")
    val showTproxyWarning = tproxyCapable == "false" && (isTproxy || tetherMode == "proxy")

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.root_settings_title),
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
            item { RestartRequiredHint() }
            item { SmallTitle(text = stringResource(R.string.root_section_device)) }
            groupedCardItems(
                keyPrefix = "root_device",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("tunDevice") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_tun_device),
                            summary = profileTunDevice?.let {
                                stringResource(R.string.root_tun_device_profile_summary, it)
                            } ?: appTunDeviceOverride?.let {
                                stringResource(R.string.root_tun_device_override_summary, it)
                            } ?: effectiveTunDevice,
                            onClick = {
                                tunDeviceTextState.edit { replace(0, length, effectiveTunDevice) }
                                showTunDeviceDialog = true
                            },
                            enabled = canEditTunDevice,
                        )
                    },
                    CardItem("jumboMtu") {
                        SwitchPreference(
                            title = stringResource(R.string.root_tun_jumbo_mtu_title),
                            summary = stringResource(R.string.root_tun_jumbo_mtu_summary),
                            checked = jumboMtu,
                            onCheckedChange = {
                                jumboMtu = it
                                storage.putString(StorageKeys.ROOT_TUN_JUMBO_MTU, it.toString())
                                onJumboMtuChange(it)
                            },
                        )
                    },
                    CardItem("allowIpv6") {
                        SwitchPreference(
                            title = stringResource(R.string.vpn_allow_ipv6),
                            summary = stringResource(R.string.vpn_allow_ipv6_summary),
                            checked = allowIpv6,
                            onCheckedChange = {
                                allowIpv6 = it
                                storage.putString(StorageKeys.VPN_ALLOW_IPV6, it.toString())
                            },
                        )
                    },
                ),
            )
            item { SmallTitle(text = stringResource(R.string.root_section_tether)) }
            if (showTproxyWarning) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.errorContainer,
                            contentColor = MiuixTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.root_tether_tproxy_unavailable_warning),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            groupedCardItems(
                keyPrefix = "root_tether",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("mode") {
                        if (isTproxy || isEbpf) {
                            // TPROXY：整条链路由 iptables 透明代理；EBPF：热点劫持同样不归 App 管，
                            // 两种模式都不需要 BYPASS/PROXY 切换
                            Text(
                                text = stringResource(
                                    if (isTproxy) {
                                        R.string.root_tproxy_tether_note
                                    } else {
                                        R.string.root_ebpf_tether_note
                                    }
                                ),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        } else {
                            val tetherItems = listOf(bypassLabel, proxyLabel)
                            val selectedIndex = tetherModeValues.indexOf(tetherMode).coerceAtLeast(0)
                            OverlayDropdownPreference(
                                title = stringResource(R.string.root_tether_mode_title),
                                summary = tetherItems[selectedIndex],
                                items = tetherItems,
                                selectedIndex = selectedIndex,
                                onSelectedIndexChange = { index ->
                                    val value = tetherModeValues[index]
                                    tetherMode = value
                                    storage.putString(StorageKeys.ROOT_TETHER_MODE, value)
                                },
                            )
                        }
                    },
                    CardItem("ifaces") {
                        ArrowPreference(
                            title = stringResource(R.string.root_tether_ifaces_title),
                            summary = tetherInterfaceSummary(tetherIfaces),
                            onClick = { showTetherDialog = true },
                        )
                    },
                    CardItem("forceReapply") {
                        SwitchPreference(
                            title = stringResource(R.string.root_attach_force_reapply_title),
                            summary = stringResource(R.string.root_attach_force_reapply_summary),
                            checked = forceReapply,
                            onCheckedChange = {
                                forceReapply = it
                                storage.putString(StorageKeys.ROOT_ATTACH_FORCE_REAPPLY, it.toString())
                            },
                        )
                    },
                ),
            )
            item { SmallTitle(text = stringResource(R.string.root_section_system)) }
            groupedCardItems(
                keyPrefix = "root_system",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("systemIpv6") {
                        SwitchPreference(
                            title = stringResource(R.string.root_system_ipv6_title),
                            summary = systemIpv6Summary,
                            checked = systemIpv6,
                            onCheckedChange = { value ->
                                // 先在 root 下真正执行，成功后才记录开关状态
                                systemScope.launch {
                                    val ok = withContext(Dispatchers.IO) { SystemIpv6.apply(context, value) }
                                    if (ok) {
                                        systemIpv6 = value
                                        storage.putString(StorageKeys.ROOT_SYSTEM_IPV6, value.toString())
                                        apnRows = withContext(Dispatchers.IO) { SystemIpv6.readPreferApn(context) }
                                        // 配置已改写：代理在运行中则自动重启以生效（未运行时 restartWhenReady 不做任何事）
                                        activeProfileId?.let { serviceController.restartWhenReady(it) }
                                    } else {
                                        showToast(ipv6ApplyFailedText)
                                    }
                                }
                            },
                        )
                    },
                ),
            )
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }

    WindowDialog(
        show = showTunDeviceDialog,
        title = stringResource(R.string.settings_tun_device),
        onDismissRequest = { showTunDeviceDialog = false },
    ) {
        TextField(
            state = tunDeviceTextState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.network_input_value),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showTunDeviceDialog = false },
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val value = tunDeviceTextState.text.toString().trim().ifEmpty { DEFAULT_TUN_DEVICE }
                    storage.putString(StorageKeys.ROOT_TUN_DEVICE, value)
                    tunDevice = value
                    showTunDeviceDialog = false
                },
            )
        }
    }

    TetherInterfaceEditDialog(
        show = showTetherDialog,
        title = stringResource(R.string.root_tether_ifaces_title),
        initialValue = tetherIfaces,
        onDismiss = { showTetherDialog = false },
        onConfirm = { csv ->
            tetherIfaces = csv
            storage.putString(StorageKeys.ROOT_TETHER_IFACES, csv)
        },
    )
}

private const val DEFAULT_TUN_DEVICE = "Mishka"

private const val DEFAULT_TETHER_IFACES = "wlan1,wlan2"
