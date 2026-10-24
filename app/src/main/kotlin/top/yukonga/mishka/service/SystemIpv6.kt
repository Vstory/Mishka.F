package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import top.yukonga.mishka.R
import top.yukonga.mishka.custom.forms.FormValues
import top.yukonga.mishka.custom.forms.YamlDoc
import top.yukonga.mishka.custom.forms.YamlNode
import top.yukonga.mishka.custom.forms.YamlPatch
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import java.io.File

/**
 * 系统 IPv6 开关（ROOT 模式）。逻辑移植自 jieluojun/mihomo_box 的 system_ipv6_sync / apn4 子系统：
 *
 * - 关（默认）：非蜂窝网卡（wlan 等；lo / 蜂窝 / 热点下游除外）写 disable_ipv6=1，并关闭 RA / autoconf；
 *   上网 APN（preferapn，跳过 IMS 承载）的 protocol / roaming_protocol 改为 IP，原值记入状态文件；
 * - 开：disable_ipv6 归 0、恢复 RA / autoconf，按状态文件还原 APN 协议；
 *   开关同时联动活动订阅 config.yaml 里的 IPv6 开关（顶层 ipv6、dns.ipv6、eBPF 监听器的 local / shared.ipv6），
 *   需重启代理生效；加密订阅无法改写，跳过；
 * - 关态下 root 常驻对账循环每 5 秒检查一次（飞行模式 / 切网后 netd 会把值放回去），漂移追打有 60 秒冷却；
 * - 脚本见 res/raw/system_ipv6.sh；状态与日志在应用私有目录 files/system_ipv6/。
 */
object SystemIpv6 {

    private const val TAG = "SystemIpv6"
    private const val STATE_DIR_NAME = "system_ipv6"
    private const val SCRIPT_NAME = "system_ipv6.sh"

    /** 一条 APN 记录：content://telephony/carriers 的 preferapn 行。 */
    data class ApnRow(
        val id: String,
        val apn: String,
        val protocol: String,
        val roaming: String,
        val type: String,
    ) {
        val isIms: Boolean get() = type.contains("ims", ignoreCase = true)
    }

    fun stateDir(context: Context): File =
        File(context.filesDir, STATE_DIR_NAME).apply { mkdirs() }

    /** 把 res/raw 里的脚本复制到私有目录（每次覆盖，保证与应用版本一致）。 */
    private fun ensureScript(context: Context): File {
        val script = File(stateDir(context), SCRIPT_NAME)
        context.resources.openRawResource(R.raw.system_ipv6).use { input ->
            script.outputStream().use { input.copyTo(it) }
        }
        return script
    }

    /** 当前开关的期望值（默认 false = 关闭系统 IPv6）。 */
    fun isEnabled(storage: PlatformStorage): Boolean =
        storage.getString(StorageKeys.ROOT_SYSTEM_IPV6, "false") == "true"

    /**
     * 应用开关（需要 root）。
     * - enable = true：一次性恢复（还原 APN、disable_ipv6=0）并结束常驻循环；
     * - enable = false：启动常驻对账循环（立即禁用一次，之后每 5 秒对账）。
     * @return root 执行是否成功
     */
    fun apply(context: Context, enable: Boolean): Boolean {
        val activeId = PlatformStorage(context).getString(StorageKeys.ACTIVE_PROFILE_UUID, "").ifEmpty { null }
        val configSynced = syncConfigFile(context, enable, activeId)
        val script = ensureScript(context)
        val dir = stateDir(context)
        val q = RootHelper.escapeShellSingleQuoted(script.absolutePath)
        val d = RootHelper.escapeShellSingleQuoted(dir.absolutePath)
        val log = RootHelper.escapeShellSingleQuoted(File(dir, "log").absolutePath)
        // 关态先同步做一次对账（禁用 IPv6 + 改写 APN），返回时即已生效，设置页随后读取到的是新值；
        // 再拉起后台常驻循环（脚本自己写 pid 文件，被新的循环替换时退出）。
        val cmd = if (enable) {
            "sh $q once 1 $d >> $log 2>&1\n"
        } else {
            "sh $q once 0 $d >> $log 2>&1\nsh $q watch 0 $d >> $log 2>&1 </dev/null &\n"
        }
        val code = RootHelper.runRootScriptHeredoc(cmd, 30)
        Log.i(TAG, "apply enable=$enable config=$configSynced exit=$code")
        return code == 0
    }

    /**
     * 把开关写进活动订阅的 config.yaml：顶层 ipv6 必写（缺省即内核默认开启）；dns.ipv6 与 eBPF 监听器的
     * local / shared.ipv6 只在所在块已存在时写，不为没有配置的功能凭空新增配置段。
     * 原子替换；加密订阅（age）或无活动订阅时跳过，返回 false。
     * 订阅 ID 由调用方传入：切换开关时用 ACTIVE_PROFILE_UUID，启动时用本次启动的订阅（不能用运行期记录的
     * ROOT_ACTIVE_SUBSCRIPTION_ID，它在代理停止时被清空，且启动时还未写入）。
     * 启动 ROOT 代理时在复制到 runtime/ 之前调用一次，保证 mihomo 读到的副本与开关一致。
     */
    fun syncConfigFile(context: Context, enable: Boolean, subscriptionId: String?): Boolean {
        val id = subscriptionId
        if (id == null) {
            Log.i(TAG, "syncConfigFile: no active subscription, skipped")
            return false
        }
        val file = ProfileFileOps.getSubscriptionConfigFile(context, id)
        if (!file.isFile || file.length() == 0L) {
            Log.w(TAG, "syncConfigFile: config.yaml missing or empty, skipped")
            return false
        }
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.trimStart().startsWith("-----BEGIN AGE ENCRYPTED FILE-----")) {
                Log.w(TAG, "syncConfigFile: age-encrypted subscription, IPv6 switches not rewritten")
                return false
            }
            val (newText, changed) = setConfigIpv6(text, enable)
            if (newText != text) {
                val tmp = File(file.parentFile, "config.yaml.ipv6.tmp")
                tmp.writeText(newText, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw java.io.IOException("rename config.yaml.ipv6.tmp failed")
                }
            }
            Log.i(TAG, "syncConfigFile enable=$enable changed=$changed")
            true
        } catch (e: Exception) {
            Log.w(TAG, "syncConfigFile failed: ${e.message}")
            false
        }
    }

    /** 返回改写后的 YAML 与实际改动的键（用于日志）。 */
    private fun setConfigIpv6(text: String, enable: Boolean): Pair<String, List<String>> {
        var doc = YamlDoc.parse(text)
        val changed = mutableListOf<String>()
        doc = YamlPatch.setValue(doc, listOf("ipv6"), enable)
        changed += "ipv6"
        if (doc.get(listOf("dns"))?.kind == YamlNode.Kind.MAP) {
            doc = YamlPatch.setValue(doc, listOf("dns", "ipv6"), enable)
            changed += "dns.ipv6"
        }
        val count = doc.get(listOf("listeners"))
            ?.takeIf { it.kind == YamlNode.Kind.SEQ }?.items?.size ?: 0
        for (index in 0 until count) {
            val type = FormValues.readRaw(doc, listOf("listeners", index, "type"))
            if (type?.equals("ebpf", ignoreCase = true) != true) continue
            for (role in listOf("local", "shared")) {
                if (doc.get(listOf("listeners", index, role))?.kind != YamlNode.Kind.MAP) continue
                doc = YamlPatch.setValue(doc, listOf("listeners", index, role, "ipv6"), enable)
                changed += "listeners[$index].$role.ipv6"
            }
        }
        return doc.dump() to changed
    }

    /** 应用启动 / 开机后调用：ROOT 可用时按期望值立即生效（默认关闭 → 立即禁用系统 IPv6）。 */
    fun onRootReady(context: Context, storage: PlatformStorage) {
        apply(context, isEnabled(storage))
    }

    /** 开机 / 应用升级时由 BootReceiver 调用：仅当之前已确认 ROOT 可用时才执行（后台线程，不阻塞广播）。 */
    fun onBootAsync(context: Context) {
        val storage = PlatformStorage(context)
        if (storage.getString(StorageKeys.HAS_ROOT, "false") != "true") return
        Thread({ onRootReady(context, storage) }, "system-ipv6-boot").apply { isDaemon = true }.start()
    }

    /** 协议值的可读文案（IP = 仅 IPv4 等）。未知值原样返回。 */
    fun protocolLabel(value: String): String = when (value.uppercase()) {
        "IP" -> "IPv4"
        "IPV6" -> "IPv6"
        "IPV4V6" -> "IPv4/IPv6"
        "PPP" -> "PPP"
        else -> value.ifBlank { "-" }
    }

    /** 读取当前首选上网 APN（root 查询，并发读取多个 subId）。失败或无 root 返回空列表。 */
    fun readPreferApn(context: Context): List<ApnRow> {
        val script = ensureScript(context)
        val q = RootHelper.escapeShellSingleQuoted(script.absolutePath)
        val d = RootHelper.escapeShellSingleQuoted(stateDir(context).absolutePath)
        return try {
            val process = ProcessBuilder("su", "-c", "sh $q status 0 $d")
                .redirectErrorStream(true)
                .start()
            RootHelper.awaitDrained(process, 15).output.lineSequence().mapNotNull { line ->
                val parts = line.trim().split('|')
                if (parts.size < 5 || parts[0].isBlank()) null
                else ApnRow(parts[0], parts[1], parts[2], parts[3], parts[4])
            }.toList()
        } catch (e: Exception) {
            Log.w(TAG, "readPreferApn failed: ${e.message}")
            emptyList()
        }
    }
}
