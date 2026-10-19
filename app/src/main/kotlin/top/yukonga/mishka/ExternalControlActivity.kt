package top.yukonga.mishka

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import top.yukonga.mishka.platform.ExternalProxyAction
import top.yukonga.mishka.platform.ExternalProxyOutcome
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.service.VpnPermissionActivity

/**
 * Automation 跳板：外部按「类名 + action」发显式 intent 启停代理，做法照 CMFA。
 * 深链导入仍归 ExternalImportActivity，本类不承接。
 *
 * 用有窗口的透明主题是必须的：Android 12 起后台应用不能启动前台服务，「应用有一个 Activity 被
 * 启动」正是拿到豁免的条件，换成 Theme.NoDisplay 会让「启动」静默失败。
 *
 * 空 taskAffinity 让本类不属于任何 task，NEW_TASK 启动时系统只能新建 task —— 单靠 MainActivity
 * 的 singleTask 实测挡不住主界面被前置。给出入都取消转场，避免把一次自动化请求放大成可见的切换。
 */
class ExternalControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        when (intent?.action) {
            ACTION_START_PROXY -> controlProxy(ExternalProxyAction.Start)
            ACTION_STOP_PROXY -> controlProxy(ExternalProxyAction.Stop)
            ACTION_TOGGLE_PROXY -> controlProxy(ExternalProxyAction.Toggle)
            ACTION_RESTART_PROXY -> controlProxy(ExternalProxyAction.Restart)
            else -> finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * 启停一律经 [ProxyServiceController]，与磁贴共用同一份状态判定与订阅校验 —— 本 Activity 不碰
     * Service，也不自己判「该不该启动」。
     */
    private fun controlProxy(action: ExternalProxyAction) {
        when (val outcome = ProxyServiceController(this).applyExternalAction(action)) {
            ExternalProxyOutcome.Started -> showToast(getString(R.string.external_proxy_started))
            ExternalProxyOutcome.Stopped -> showToast(getString(R.string.external_proxy_stopped))
            ExternalProxyOutcome.Restarted -> showToast(getString(R.string.external_proxy_restarted))
            ExternalProxyOutcome.AlreadyRunning ->
                showToast(getString(R.string.external_proxy_already_running))

            ExternalProxyOutcome.NotRunning ->
                showToast(getString(R.string.external_proxy_not_running))

            // 过渡态（Starting / Stopping）不播报：用户刚点过或刚被自动化触发过，再弹一条是噪音
            ExternalProxyOutcome.Busy -> Unit

            // 无可用订阅已由 resolveStartSubscriptionId 播报并置 Error，不重复提示
            ExternalProxyOutcome.NoSubscription -> Unit

            // 本 Activity 就是 Activity 上下文，授权对话框可直接拉（Tile 那条路径拉不了）
            is ExternalProxyOutcome.NeedVpnPermission -> startActivity(
                Intent(this, VpnPermissionActivity::class.java)
                    .putExtra(VpnPermissionActivity.EXTRA_SUBSCRIPTION_ID, outcome.subscriptionId)
            )
        }
        finish()
    }

    companion object {
        /** Automation 入口：外部按「类名 + 下列 action」发显式 intent，用法见 README「自动化」。 */
        const val ACTION_START_PROXY = "top.yukonga.mishka.action.START_PROXY"
        const val ACTION_STOP_PROXY = "top.yukonga.mishka.action.STOP_PROXY"
        const val ACTION_TOGGLE_PROXY = "top.yukonga.mishka.action.TOGGLE_PROXY"
        const val ACTION_RESTART_PROXY = "top.yukonga.mishka.action.RESTART_PROXY"
    }
}
