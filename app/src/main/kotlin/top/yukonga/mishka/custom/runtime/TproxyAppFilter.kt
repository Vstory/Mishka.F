package top.yukonga.mishka.custom.runtime

/**
 * Tproxy 分应用名单（纯 Kotlin，不依赖 Android，便于单独编译与单测）。
 *
 * 语义对齐 mihomo_box 的 Tproxy 黑白名单：
 * - `include-package` 非空 → 白名单：只有名单内应用的本机流量进入 Tproxy；
 * - 否则 `exclude-package` 非空 → 黑名单：名单内应用直连，其余应用走 Tproxy；
 * - 否则全部应用走 Tproxy。include 优先。
 * 热点 / USB 共享的转发流量不受名单影响（仍全部接管）。
 */
data class TproxyAppFilter(
    val mode: Mode,
    val packages: List<String> = emptyList(),
    val androidUsers: List<Int> = emptyList(),
) {
    enum class Mode { All, Include, Exclude }

    companion object {
        /** Android 多用户 UID 规则：uid = 安卓用户 ID * 100000 + appId。 */
        const val USER_UID_RANGE = 100_000

        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")

        /**
         * 由配置里的原始名单挑出生效模式。`include` / `exclude` 为 null 表示未设置。
         * 空白项忽略；include 只要写了条目（即使之后解析不到 UID）就仍按白名单处理。
         */
        fun pick(
            include: List<String>?,
            exclude: List<String>?,
            androidUsers: List<Int> = emptyList(),
        ): TproxyAppFilter {
            val inc = cleanPackages(include)
            if (inc.isNotEmpty()) return TproxyAppFilter(Mode.Include, inc, androidUsers)
            val exc = cleanPackages(exclude)
            if (exc.isNotEmpty()) return TproxyAppFilter(Mode.Exclude, exc, androidUsers)
            return TproxyAppFilter(Mode.All, emptyList(), androidUsers)
        }

        /** 基准 UID 及其在各安卓用户下的同一应用 UID；基准 UID 始终保留。 */
        fun expandUid(uid: Int, androidUsers: Collection<Int>): Set<Int> {
            val appId = uid % USER_UID_RANGE
            val out = linkedSetOf(uid)
            for (user in androidUsers) {
                if (user >= 0) out.add(user * USER_UID_RANGE + appId)
            }
            return out
        }

        /** 与 mihomo_box 相同的包名字符集：字母开头，仅含字母、数字、下划线与点。 */
        fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

        private fun cleanPackages(list: List<String>?): List<String> =
            list.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
