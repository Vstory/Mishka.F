package top.yukonga.mishka

import androidx.compose.runtime.Composable
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.ui.navigation.AppNavigation
import top.yukonga.mishka.ui.theme.MishkaTheme
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.AutomationViewModel
import top.yukonga.mishka.viewmodel.BackupViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel

@Composable
fun App(
    themeConfig: ThemeConfig = ThemeConfig(),
    onThemeConfigChange: (ThemeConfig) -> Unit = {},
    homeViewModel: HomeViewModel? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    overrideProfileViewModel: OverrideProfileViewModel? = null,
    proxyViewModel: ProxyViewModel? = null,
    logViewModel: LogViewModel? = null,
    providerViewModel: ProviderViewModel? = null,
    connectionViewModel: ConnectionViewModel? = null,
    dnsQueryViewModel: DnsQueryViewModel? = null,
    networkSettingsViewModel: NetworkSettingsViewModel? = null,
    metaSettingsViewModel: MetaSettingsViewModel? = null,
    externalControlViewModel: ExternalControlViewModel? = null,
    automationViewModel: AutomationViewModel? = null,
    appProxyViewModel: AppProxyViewModel? = null,
    filePicker: FilePicker? = null,
    storage: PlatformStorage? = null,
    bootStartManager: BootStartManager? = null,
    mihomoVersion: String = "",
    onScanQR: (onResult: (String) -> Unit) -> Unit = {},
    wifiPolicyController: WifiPolicyController? = null,
    onRequestWifiPermission: (onResult: (Boolean) -> Unit) -> Unit = {},
    onPredictiveBackChange: (Boolean) -> Unit = {},
    onHideTaskCardChange: (Boolean) -> Unit = {},
    hasRootPermission: Boolean = true,
    deepLinkImport: Pair<String, String>? = null,
    onDeepLinkImportConsumed: () -> Unit = {},
    backupViewModel: BackupViewModel? = null,
    onRestartApp: () -> Unit = {},
) {
    MishkaTheme(themeConfig = themeConfig) {
        AppNavigation(
            themeConfig = themeConfig,
            onThemeConfigChange = onThemeConfigChange,
            homeViewModel = homeViewModel,
            subscriptionViewModel = subscriptionViewModel,
            overrideProfileViewModel = overrideProfileViewModel,
            proxyViewModel = proxyViewModel,
            logViewModel = logViewModel,
            providerViewModel = providerViewModel,
            connectionViewModel = connectionViewModel,
            dnsQueryViewModel = dnsQueryViewModel,
            networkSettingsViewModel = networkSettingsViewModel,
            metaSettingsViewModel = metaSettingsViewModel,
            externalControlViewModel = externalControlViewModel,
            automationViewModel = automationViewModel,
            appProxyViewModel = appProxyViewModel,
            filePicker = filePicker,
            storage = storage,
            bootStartManager = bootStartManager,
            mihomoVersion = mihomoVersion,
            onScanQR = onScanQR,
            wifiPolicyController = wifiPolicyController,
            onRequestWifiPermission = onRequestWifiPermission,
            onPredictiveBackChange = onPredictiveBackChange,
            onHideTaskCardChange = onHideTaskCardChange,
            hasRootPermission = hasRootPermission,
            deepLinkImport = deepLinkImport,
            onDeepLinkImportConsumed = onDeepLinkImportConsumed,
            backupViewModel = backupViewModel,
            onRestartApp = onRestartApp,
        )
    }
}
