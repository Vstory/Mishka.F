package top.yukonga.mishka

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.view.WindowInsetsControllerCompat
import org.koin.android.ext.android.get
import top.yukonga.mishka.custom.panel.PanelProxyOverride
import top.yukonga.mishka.custom.panel.PanelProxyStatus
import top.yukonga.mishka.custom.panel.PanelScreen
import top.yukonga.mishka.custom.panel.WebViewPreloader
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.ui.theme.MishkaTheme
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.ui.theme.readThemeConfig
import top.yukonga.mishka.ui.theme.resolveIsDark

/**
 * 外部面板（Web 界面）的独立 Activity——面板不再挂在主界面的 Compose 树里。
 *
 * **为什么单独开一个 Activity**：面板 WebView 原来是主界面导航栈里的一屏，重进要跟着
 * 转场 / 导航重建实例，白闪、内容缺失都出在这一层；把它挪到独立 Activity 后，每次进入
 * 都是「干净的新 WebView + 加载入口 URL」这一条确定路径（和 box.app 的重进语义一致），
 * 退出即 finish、实例整体丢弃，不再和主界面的转场、弹层窗口抢合成。
 *
 * **登录态不受影响**：面板自己的控制器地址 / 密钥存在 WebView 的 localStorage，Cookie
 * 也在系统 CookieManager 里，两者都跨实例保留——丢的只是 WebView 对象，不是数据。
 *
 * **外观跟主界面一致**：主题走 [MishkaTheme]（与 [App] 同一份配色与 CompositionLocal），
 * 系统栏按 [ThemeConfig] 做 edge-to-edge，行为和 MainActivity 相同。
 *
 * **返回键**：页内历史由 [PanelScreen] 内部的 BackHandler 先接（canGoBack 时退网页历史），
 * 退无可退时系统默认收尾本 Activity，等同于「退出面板」。
 */
class PanelActivity : ComponentActivity() {

    private var latestThemeConfig: ThemeConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 预热主界面已经调过一次；这里是幂等的兜底（例如进程恢复时先起面板 Activity）
        WebViewPreloader.preload(this)

        val storage: PlatformStorage = get()
        val proxyResolver: SubscriptionProxyResolver = get()
        val themeConfig = readThemeConfig(storage)
        updateEdgeToEdge(themeConfig)

        setContent {
            MishkaTheme(themeConfig = themeConfig) {
                // [mishka-custom] 外网请求要走 mixed-port：代理覆盖生效之后才组合面板，面板页的首个请求就已经走代理
                val proxyStatus by produceState<PanelProxyStatus?>(initialValue = null) {
                    value = PanelProxyOverride.prepare(applicationContext, proxyResolver)
                }
                proxyStatus?.let { status ->
                    PanelScreen(onBack = { finish() }, proxyStatus = status)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manifest configChanges=uiMode 吞掉了深浅色切换的重建，前台期间系统定时深色模式
        // 翻转既无焦点变化也无其它回调，只有这里能重新应用系统栏外观
        latestThemeConfig?.let(::updateEdgeToEdge)
    }

    private fun updateEdgeToEdge(themeConfig: ThemeConfig) {
        latestThemeConfig = themeConfig
        val isDark = themeConfig.resolveIsDark(
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        val systemBarStyle = themeConfig.systemBarStyle()
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle,
        )
        enforceSystemBarsAppearance(isDark)
        window.decorView.post {
            enforceSystemBarsAppearance(isDark)
        }
    }

    private fun ThemeConfig.systemBarStyle(): SystemBarStyle = when (colorMode) {
        1 -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        2 -> SystemBarStyle.dark(Color.TRANSPARENT)
        else -> SystemBarStyle.auto(
            lightScrim = Color.TRANSPARENT,
            darkScrim = Color.TRANSPARENT,
            detectDarkMode = { resources ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            },
        )
    }

    private fun enforceSystemBarsAppearance(isDark: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    companion object {
        private const val ACTION_OPEN_PANEL = "top.yukonga.mishka.action.OPEN_PANEL"

        /** 主界面「外部面板」入口：显式 intent 只在本应用内跳转（manifest 里 exported=false）。 */
        fun createIntent(context: Context): Intent =
            Intent(context, PanelActivity::class.java).setAction(ACTION_OPEN_PANEL)
    }
}
