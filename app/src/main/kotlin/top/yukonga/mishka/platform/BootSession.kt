package top.yukonga.mishka.platform

import android.content.Context
import android.provider.Settings

/**
 * 判定「设备自上次启动代理以来是否重启过」。ROOT 模式的 mihomo 是独立 root 进程，设备重启会
 * 杀掉它但持久化的 PID 仍在，仅凭「PID 非空」会误判成「进程仍活」。
 *
 * 判据是 `Settings.Global.BOOT_COUNT`：公开 API、免权限、每次开机递增，精确且没有时间窗口。
 */
object BootSession {

    /** 记录当前 boot session。ROOT 启动成功后调用，与 PID / secret 一同持久化。 */
    fun mark(context: Context, storage: PlatformStorage) {
        storage.putString(StorageKeys.ROOT_BOOT_COUNT, bootCount(context)?.toString() ?: "")
    }

    fun clear(storage: PlatformStorage) {
        storage.putString(StorageKeys.ROOT_BOOT_COUNT, "")
    }

    /**
     * 设备是否在 [mark] 之后重启过；取不到 BOOT_COUNT 时按「没重启」处理。
     *
     * **宁可漏判不可误判**：漏判只多走一次 attach 尝试，三重校验会挡下过期 PID；误判会清掉
     * 仍有效的 PID，让活着的 mihomo 变成孤儿进程而界面显示未运行。故不做「时钟跳变即重启」
     * 这类推测。
     */
    fun hasRebootedSince(context: Context, storage: PlatformStorage): Boolean {
        val stored = storage.getString(StorageKeys.ROOT_BOOT_COUNT, "").toIntOrNull() ?: return false
        val current = bootCount(context) ?: return false
        return current != stored
    }

    /** 系统开机计数；键不存在时 `getInt` 抛异常，取不到一律按 null 处理 */
    private fun bootCount(context: Context): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrNull()
            ?.takeIf { it > 0 }
}
