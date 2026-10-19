package top.yukonga.mishka

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import top.yukonga.mishka.platform.showToast
import java.util.UUID

/**
 * clash://install-config 深链跳板：解析 url/name/update-interval 参数后转发 MainActivity，
 * 预填添加订阅表单，用户确认保存才真正导入。
 * 浏览器把本 Activity 启进自己的 task，必须 NEW_TASK 路由回 Mishka 自身 task；
 * SINGLE_TOP + CLEAR_TOP 让已存活的 MainActivity 走 onNewIntent 而非销毁重建。
 * nonce 供 MainActivity 去重：进程死亡恢复会重放旧 intent，仅凭 savedInstanceState 无法
 * 与「恢复态下送达的新深链」区分。
 */
class ExternalImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本 Activity 是 exported：显式 intent 可塞非层级 URI（clash:install-config），
        // 其上调 getQueryParameter 会抛 UnsupportedOperationException
        val uri = intent?.data?.takeIf { it.isHierarchical }
        val url = uri?.getQueryParameter(QUERY_URL)?.trim()
        if (url.isNullOrEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            showToast(getString(R.string.deep_link_invalid))
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_IMPORT_SUBSCRIPTION
                putExtra(EXTRA_IMPORT_URL, url)
                putExtra(EXTRA_IMPORT_NAME, uri.getQueryParameter(QUERY_NAME)?.trim().orEmpty())
                putExtra(
                    EXTRA_IMPORT_INTERVAL_MINUTES,
                    uri.getQueryParameter(QUERY_UPDATE_INTERVAL)?.toLongOrNull()?.coerceAtLeast(0) ?: 0L,
                )
                putExtra(EXTRA_IMPORT_NONCE, UUID.randomUUID().toString())
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        )
        finish()
    }

    companion object {
        const val ACTION_IMPORT_SUBSCRIPTION = "top.yukonga.mishka.action.IMPORT_SUBSCRIPTION"
        const val EXTRA_IMPORT_URL = "import_url"
        const val EXTRA_IMPORT_NAME = "import_name"
        const val EXTRA_IMPORT_INTERVAL_MINUTES = "import_interval_minutes"
        const val EXTRA_IMPORT_NONCE = "import_nonce"

        private const val QUERY_URL = "url"
        private const val QUERY_NAME = "name"
        private const val QUERY_UPDATE_INTERVAL = "update-interval"
    }
}

/** 深链导入请求：MainActivity 解析 intent 后经 App/AppNavigation 透传，驱动导航到预填的添加订阅页 */
data class DeepLinkImportRequest(
    val url: String,
    val name: String,
    val intervalMinutes: Long,
)
