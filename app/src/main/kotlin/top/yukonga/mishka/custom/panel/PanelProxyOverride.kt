package top.yukonga.mishka.custom.panel

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyState
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executor

/** 进入面板时的代理决议：决定面板的外网请求能不能走 mihomo。 */
sealed interface PanelProxyStatus {
    /** 外网请求经 127.0.0.1:[port]（mixed-port）进入 mihomo，按规则分流。 */
    data class Proxied(val port: Int) : PanelProxyStatus

    /** 代理未运行：外网请求直连（本地面板本来就打不开；外部面板照常可用）。 */
    data object NotRunning : PanelProxyStatus

    /**
     * 代理在运行，但外网请求没能接上（拿不到 mixed-port、WebView 不支持代理覆盖、设置失败）。
     * 外网仍是直连，必须明确提示，不能静默给出国内出口的结果。
     */
    data object Unavailable : PanelProxyStatus
}

/**
 * 外部面板（WebView）的外网请求改走 mihomo mixed-port。
 *
 * **根因**：Mishka 自身 UID 被代理规则排除——VPN 下 `addDisallowedApplication(packageName)`，
 * TPROXY 下 `out-bypass-self`。WebView 的请求由这个 UID 发出，于是直连：域名交给系统 DNS 解析，
 * 国外站点解析成国内 IP；YouTube 等被墙站点的测速请求也直接失败。
 * box.app 的面板 / 延迟 / 出口 IP 能正确工作，是因为它的 App 流量没有被排除、由内核透明代理收走；
 * Mishka 排除了自身 UID，所以只能由 App 把 WebView 显式指向本机 mixed-port。
 *
 * **做法**：[ProxyController.setProxyOverride] 把 WebView 网络栈的代理设为 `127.0.0.1:<mixed-port>`。
 * 这是 WebView 原生的代理覆盖：GET / POST / WebSocket、HTTPS CONNECT、重定向、Cookie、缓存都由
 * Chromium 网络栈处理，域名不在本地解析（交给 mihomo）。它是进程级设置，本进程所有 WebView 一致。
 * 本机与局域网地址绕过代理：面板自己的控制器、路由器上的面板应当直连（个别 WebView 不接受绕过规则时，由 mihomo 规则决定）。
 *
 * 依赖 androidx.webkit（`PROXY_OVERRIDE` 特性）；系统 WebView 不支持时进入 [PanelProxyStatus.Unavailable]。
 */
internal object PanelProxyOverride {

    private const val LOOPBACK = "127.0.0.1"
    private val LAN_BYPASS_RULES = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    private const val RESOLVE_TIMEOUT_MS = 3_000L
    private const val APPLY_TIMEOUT_MS = 3_000L

    /** 当前面板所用的本机代理端口；未代理时为 null。 */
    @Volatile
    private var activePort: Int? = null

    /** 与面板同一出口的 Java [Proxy]，供面板下载等 App 侧 HttpURLConnection 使用；未代理时为 [Proxy.NO_PROXY]。 */
    fun javaProxy(): Proxy {
        val port = activePort ?: return Proxy.NO_PROXY
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, port))
    }

    /**
     * 进入面板前调用：解析 mixed-port 并设置 WebView 代理覆盖。挂起直到生效（或超时），
     * 返回后再加载面板页，首个请求就已经走代理。
     */
    suspend fun prepare(context: Context, resolver: SubscriptionProxyResolver): PanelProxyStatus {
        activePort = null
        // 先看代理是否在运行：没运行时直连是正确状态，不能因为 WebView 不支持而误报「代理在运行」
        if (ProxyServiceBridge.state.value.state != ProxyState.Running) {
            applyOverride(context, port = null)
            return PanelProxyStatus.NotRunning
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return PanelProxyStatus.Unavailable
        }
        val port = resolvePort(resolver)
        if (port != null && applyOverride(context, port)) {
            activePort = port
            return PanelProxyStatus.Proxied(port)
        }
        // 清掉上一次残留的代理，避免指向已经停掉的端口
        applyOverride(context, port = null)
        return PanelProxyStatus.Unavailable
    }

    /** resolve 只在代理运行时返回 `http://127.0.0.1:<port>`；取不到（未开启 mixed-port / 查询失败）即 null。 */
    private suspend fun resolvePort(resolver: SubscriptionProxyResolver): Int? {
        val url = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            try {
                resolver.resolve(requireUserToggle = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }
        return url?.substringAfterLast(':')?.toIntOrNull()
    }

    /** 设置（port 非空）或清除（null）代理覆盖。返回是否已生效；超时也按已生效处理，不让面板永久卡住。 */
    private suspend fun applyOverride(context: Context, port: Int?): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        val applied = CompletableDeferred<Unit>()
        val listener = Runnable { applied.complete(Unit) }
        val executor = ContextCompat.getMainExecutor(context)
        val accepted = runCatching {
            val controller = ProxyController.getInstance()
            if (port != null) {
                setProxy(controller, port, executor, listener)
            } else {
                controller.clearProxyOverride(executor, listener)
            }
        }.isSuccess
        if (!accepted) return false
        withTimeoutOrNull(APPLY_TIMEOUT_MS) { applied.await() }
        return true
    }

    /**
     * 先用带局域网绕过的完整配置。WebView 拒绝其中的绕过规则（[IllegalArgumentException]）时，
     * 退回只含代理规则的配置：外网照样走 mihomo，局域网是否直连由 mihomo 的规则决定。
     */
    private fun setProxy(controller: ProxyController, port: Int, executor: Executor, listener: Runnable) {
        try {
            controller.setProxyOverride(buildConfig(port, lanBypass = true), executor, listener)
        } catch (_: IllegalArgumentException) {
            controller.setProxyOverride(buildConfig(port, lanBypass = false), executor, listener)
        }
    }

    private fun buildConfig(port: Int, lanBypass: Boolean): ProxyConfig {
        val builder = ProxyConfig.Builder()
            .addProxyRule("$LOOPBACK:$port")
            // 本机直连：面板自己的控制器不能绕进代理
            .addBypassRule("localhost")
            .addBypassRule(LOOPBACK)
        if (lanBypass) {
            // 局域网直连：路由器上的面板等
            LAN_BYPASS_RULES.forEach { builder.addBypassRule(it) }
        }
        return builder.bypassSimpleHostnames().build()
    }
}
