package top.yukonga.mishka.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.Url
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import kotlin.time.TimeSource

/**
 * 按 mihomo 规则实测站点延迟：请求经本机 mixed-port 发出，出口由规则引擎决定，与用户真实访问该站点同一条路径。
 * `GET /proxies/{name}/delay` 对指定组的当前选中节点直接拨测、绕过规则匹配，做不到这点。
 *
 * Mishka 自身流量始终绕过 TUN，mixed-port 是唯一能让自己的请求经过 mihomo 的入口；
 * 端口解析不到时返回 [Unavailable]，由调用方决定降级方式。
 */
class RuleLatencyTester(
    private val proxyResolver: SubscriptionProxyResolver,
) {
    /**
     * @return 毫秒延迟；[Unavailable] 表示 mixed-port 不可用（无法按规则测），[Failed] 表示请求失败或超时。
     */
    suspend fun measure(url: String, timeoutMillis: Long): Int {
        // 只要代理在跑就走本机端口，与「订阅下载走代理」开关无关——此处测的是代理本身的连通性
        val proxyUrl = proxyResolver.resolve(requireUserToggle = false) ?: return Unavailable
        // 每次新建 client：复用连接池会省掉 TCP 握手，让重复刷新测出的数字逐次偏低，口径不稳
        val client = buildClient(proxyUrl, timeoutMillis)
        return try {
            val start = TimeSource.Monotonic.markNow()
            client.get(url)
            start.elapsedNow().inWholeMilliseconds.toInt()
        } catch (_: Throwable) {
            Failed
        } finally {
            client.close()
        }
    }

    private fun buildClient(proxyUrl: String, timeoutMillis: Long) = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = timeoutMillis
            requestTimeoutMillis = timeoutMillis
        }
        // 跟随 3xx 会把后续跳转的往返一并计进耗时；首个响应到达即视为连通
        followRedirects = false
        engine { proxy = ProxyBuilder.http(Url(proxyUrl)) }
    }

    companion object {
        /** mixed-port 解析不到，无法按规则测 */
        const val Unavailable = -2

        /** 请求失败 / 超时 / 目标不可达 */
        const val Failed = -1
    }
}
