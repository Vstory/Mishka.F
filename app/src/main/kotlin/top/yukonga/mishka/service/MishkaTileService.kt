package top.yukonga.mishka.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState

class MishkaTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // 系统重复绑定时会再进一次而没有配对的 onStopListening，不先取消就留下一条仍在
        // updateTile 的旧 collector
        stateJob?.cancel()
        stateJob = scope.launch {
            ProxyServiceBridge.state.collect { status ->
                val tile = qsTile ?: return@collect
                tile.state = when (status.state) {
                    ProxyState.Running -> Tile.STATE_ACTIVE
                    ProxyState.Starting -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = when (status.state) {
                    ProxyState.Running -> getString(R.string.tile_connected)
                    ProxyState.Starting -> getString(R.string.tile_connecting)
                    ProxyState.Error -> getString(R.string.tile_error)
                    else -> getString(R.string.tile_disconnected)
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val currentState = ProxyServiceBridge.state.value
        // 启动中时忽略点击，防止重复操作
        if (currentState.state == ProxyState.Starting) return

        val controller = ProxyServiceController(applicationContext)
        if (currentState.state == ProxyState.Running) {
            controller.stop()
            return
        }
        // VPN 授权对话框要求调用方是 Activity，Tile 自身不是，需透明 Activity 跳板。
        // 跳板前先 resolve 订阅，避免授权后又被 controller 拒绝。
        if (!controller.hasVpnPermission()) {
            val id = controller.resolveStartSubscriptionId() ?: return
            launchVpnPermissionActivity(id)
            return
        }
        controller.start()
    }

    private fun launchVpnPermissionActivity(subscriptionId: String) {
        val intent = Intent(this, VpnPermissionActivity::class.java).apply {
            putExtra(VpnPermissionActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
