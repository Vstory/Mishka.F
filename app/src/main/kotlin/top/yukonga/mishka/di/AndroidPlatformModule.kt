package top.yukonga.mishka.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import top.yukonga.mishka.data.backup.BackupManager
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.PlatformSystemInfo
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.service.ProfileUpdateScheduler

/**
 * Android 平台单例：均绑定 application Context（`androidContext()`）。
 * 需 Activity 上下文的资源（FilePicker / VPN 授权 launcher）由 MainActivity 直接持有，不入 Koin。
 */
val androidPlatformModule: Module = module {
    single { getAppDatabase(androidContext()) }
    single { PlatformStorage(androidContext()) }
    single { PlatformSystemInfo(androidContext()) }
    single { ProxyServiceController(androidContext()) }
    single { AppListProvider(androidContext()) }
    single { WifiPolicyController(androidContext()) }
    single { BootStartManager(androidContext()) }
    single { BackupManager(androidContext(), get(), get(), get(), get()) }
    single { ProfileUpdateScheduler(androidContext(), get(), get()) }
}
