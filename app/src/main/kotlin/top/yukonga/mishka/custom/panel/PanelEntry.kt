package top.yukonga.mishka.custom.panel

import androidx.compose.runtime.Immutable

/**
 * 外部面板（Web 界面）条目。
 *
 * [url] 为 `null` 只可能出现在内置「本地」面板上——它的地址是 `http://<控制器>/ui`，
 * 端口取自运行时 [top.yukonga.mishka.platform.ProxyServiceStatus.externalController]，
 * 编译期写不死，只能进页面时现算，因此不能和其它面板一样当常量存。
 */
@Immutable
data class PanelEntry(
    val id: String,
    val name: String,
    val url: String?,
    val isBuiltIn: Boolean,
)

/** 内置面板 id；自定义面板用 `custom_<时间戳>`，与 box.app 一致。 */
internal const val PANEL_ID_LOCAL = "local"
internal const val PANEL_ID_ZASHBOARD = "zashboard"
internal const val PANEL_ID_METACUBEXD = "metacubexd"

internal const val PANEL_URL_ZASHBOARD = "http://board.zash.run.place"
internal const val PANEL_URL_METACUBEXD = "https://metacubex.github.io/metacubexd"