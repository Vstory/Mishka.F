package top.yukonga.mishka

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.di.androidAppModule
import top.yukonga.mishka.di.androidPlatformModule
import top.yukonga.mishka.di.dataModule
import top.yukonga.mishka.di.viewModelModule
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.SystemDnsObserver
import top.yukonga.mishka.platform.initToastPlatform
import top.yukonga.mishka.service.NotificationHelper
import top.yukonga.mishka.service.ProfileFileOps
import top.yukonga.mishka.service.ProfileUpdateScheduler
import top.yukonga.mishka.service.RootHelper
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MishkaApplication : Application() {

    // application 级单例：全 app 共享一对 mihomo 客户端，按 ProxyServiceBridge.state 自动 connect/disconnect
    // 消费方（ViewModel / Service）禁止自建 MihomoApiClient/WebSocket，只 collect connectionManager.repository
    // 由 Koin dataModule 提供（内部用 dataModule 的 application 级 CoroutineScope）
    val connectionManager: MihomoConnectionManager by inject()

    // 自动更新闹钟随 imported 表对账，进程一起就接上（后台服务拉起的进程同样需要）
    private val updateScheduler: ProfileUpdateScheduler by inject()
    private val appScope: CoroutineScope by inject()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startKoin {
            androidContext(this@MishkaApplication)
            modules(dataModule, androidPlatformModule, androidAppModule, viewModelModule)
        }
        initToastPlatform(this)
        NotificationHelper.createChannels(this)
        extractGeoFiles()
        // 必须在 extractGeoFiles 之后；UA 用订阅服务白名单接受的字符串
        MishkaCoreBridge.init(
            homeDir = ProfileFileOps.getGeodataDir(this).absolutePath,
            userAgent = "ClashMetaForAndroid/${BuildConfig.VERSION_NAME}",
        )
        // JNI 导入核心与转发子进程各有独立的 DNS 全局状态，网络变化必须同时送达两边。
        appScope.launch {
            combine(SystemDnsObserver(this@MishkaApplication).observe(), connectionManager.repository) { servers, repository ->
                servers to repository
            }.collect { (servers, repository) ->
                try {
                    MishkaCoreBridge.updateSystemDns(servers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update in-process system DNS", e)
                }
                repository?.updateSystemDns(servers)?.onFailure {
                    Log.e(TAG, "Failed to update runtime system DNS", it)
                }
            }
        }
        reclaimRootOwnedImported()
        updateScheduler.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val prefs = getSharedPreferences("mishka_prefs", MODE_PRIVATE)
            val enable = prefs.getString("predictive_back", "false") == "true"
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }
    }

    /**
     * 一次性迁移：旧版本 mihomo 以 root 身份直接在 imported/{uuid}/ 下写 provider/ruleset 缓存，
     * 导致更新/删除时 app 无权限 unlink。新版改走独立 runtime/{uuid}/ 沙箱，但旧遗孤仍需 chown 回收。
     * 需要 ROOT 权限；无 root 的设备跳过（此前就不会产生 root 遗孤）。
     */
    private fun reclaimRootOwnedImported() {
        val storage = PlatformStorage(this)
        if (storage.getString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "false") == "true") return
        thread(name = "root-reclaim", isDaemon = true) {
            val imported = File(filesDir, "mihomo/imported")
            if (!imported.exists()) {
                storage.putString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "true")
                return@thread
            }
            if (RootHelper.hasRootAccess()) {
                val uid = Process.myUid()
                val ok = RootHelper.chownRecursiveAsRoot(imported.absolutePath, uid)
                Log.i(TAG, "One-shot chown imported/ to uid=$uid: ok=$ok")
                if (ok) storage.putString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "true")
            } else {
                // 无 ROOT → 不可能有旧 root 遗孤，直接打标记跳过
                storage.putString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "true")
            }
        }
    }

    /**
     * 从 assets 提取预制 GeoIP 文件到 geodata/ 共享目录。
     * 应用更新后自动替换旧文件，文件已存在且未过期则跳过。
     */
    private fun extractGeoFiles() {
        val geodataDir = ProfileFileOps.getGeodataDir(this)
        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime

        val geoFiles = listOf("geoip.metadb", "geosite.dat", "ASN.mmdb")
        for (fileName in geoFiles) {
            val target = File(geodataDir, fileName)
            if (target.exists() && target.lastModified() < updateDate) {
                target.delete()
            }
            if (!target.exists()) {
                runCatching {
                    FileOutputStream(target).use { assets.open(fileName).copyTo(it) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MishkaApplication"

        lateinit var instance: MishkaApplication
            private set

        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val method = ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}
