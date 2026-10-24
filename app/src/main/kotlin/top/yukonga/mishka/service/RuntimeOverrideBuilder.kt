package top.yukonga.mishka.service

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.DnsOverride
import top.yukonga.mishka.domain.model.ProfileOverride
import top.yukonga.mishka.custom.runtime.TproxyAppFilter
import top.yukonga.mishka.domain.model.TunOverride
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.service.RuntimeOverrideBuilder.DEFAULT_MIXED_PORT
import top.yukonga.mishka.viewmodel.AppProxyMode
import java.io.File

/**
 * 运行时 override 组装：用户持久化的 ConfigurationOverride 叠加 TUN fd / rootMode / AppProxy
 * 等运行期字段，输出到 `files/mihomo/override.run.json`。
 *
 * mihomo 启动通过 `--override-json <path>` 参数读该文件，
 * 在 yaml.Unmarshal 之后、ParseRawConfig 之前 `json.NewDecoder().Decode(rawCfg)` 注入。
 */
object RuntimeOverrideBuilder {

    private const val FILE_NAME = "override.run.json"
    internal const val DEFAULT_TUN_DEVICE = "Mishka"

    // VPN/ROOT TUN 共用 MTU。VpnService.Builder.setMtu 与 sing-tun cfg.Tun.MTU 必须同值：
    // sing-tun 在 fd 模式用 cfg.Tun.MTU 给 gvisor fdbased.New 设 endpoint 缓冲，0 时所有 read 失败。
    internal const val VPN_TUN_MTU = 9000
    internal const val VPN_GATEWAY = "172.19.0.1"
    internal const val VPN_GATEWAY6 = "fdfe:dcba:9876::1"
    internal const val VPN_PREFIX = 30
    internal const val VPN_PREFIX6 = 126
    internal const val VPN_DNS = "172.19.0.2"
    internal const val VPN_DNS6 = "fdfe:dcba:9876::2"

    // ROOT TUN 模式 sing-tun 路由常量，与 RootTetherHijacker 对齐
    // sing-tun 默认值也是 2022 / 9000，此处显式注入避免上游默认值漂移
    internal const val ROOT_TUN_TABLE = 2022
    internal const val ROOT_TUN_RULE_INDEX = 9000

    // 「通过代理更新订阅」开启且用户未显式配置 mixed-port 时的兜底默认值，
    // 确保 mihomo 一定监听 HTTP 代理端口，让 SubscriptionProxyResolver 稳定解析到
    internal const val DEFAULT_MIXED_PORT = 7890

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * 生成运行时 override 并写入 override.run.json，返回该文件绝对路径。
     *
     * secret / external-controller **不**写入 JSON —— 通过 mihomo `--secret` / `--ext-ctl`
     * CLI flag 传入（见 MihomoRunner）。profile 段固定禁用 store-selected（由 SelectionEntity 管理），
     * 启用 store-fake-ip（保留 fake-ip 缓存）。
     *
     * 按 [tunMode] 分支注入策略：
     * - [TunMode.Vpn]        VpnService + sing-tun fd，仅写 `tun.file-descriptor`
     * - [TunMode.RootTun]    sing-tun auto_route + include/exclude-package；可选为 tether 加 tproxy-port
     * - [TunMode.RootTproxy] 关闭 TUN，写 tproxy-port + dns.listen，AppProxy 交 iptables uid-owner
     * - [TunMode.RootEbpf]   关闭 TUN，不注入任何入站端口；劫持由配置内 ebpf listener 自理
     *
     * mixed-port 决策（决定 mihomo 是否监听 HTTP 代理端口，[SubscriptionProxyResolver] 据此走代理）：
     * 1. 用户 override 显式设置 → 用用户值（覆盖订阅 yaml）
     * 2. 无订阅变换且原 YAML 自带 `mixed-port` → 不注入，mihomo 沿用该值
     * 3. [subscriptionUpdateViaProxy] 启用 → 注入 [DEFAULT_MIXED_PORT] 兜底
     * 4. 有订阅变换时，此处的默认值只是兜底；runtime 在 `hub.Parse` 前按同一次脚本执行结果
     *    用 Option 恢复显式 `mixed-port`，避免默认值盖掉脚本设置。
     */
    fun buildAndWriteForRun(
        context: Context,
        userOverride: ConfigurationOverride,
        tunFd: Int,
        tunMode: TunMode,
        subscriptionUpdateViaProxy: Boolean,
        subscriptionMixedPort: Int?,
        subscriptionId: String? = null,
        effectiveRootTunDevice: String? = null,
        tproxyForTether: Boolean = false,
        effectiveRootTproxyPort: Int? = null,
        effectiveProfileListeners: JsonElement? = null,
        useProfileTunDefaults: Boolean = false,
        effectiveProfileTun: JsonElement? = null,
        preferUserTunOverrides: Boolean = false,
    ): File {
        val storage = PlatformStorage(context)
        val wifiRuntimeMode = storage.getString(StorageKeys.WIFI_POLICY_RUNTIME_MODE, "")
            .takeIf { it == "rule" || it == "global" || it == "direct" }
        val merged = userOverride.copy(
            externalController = null,
            secret = null,
            mode = wifiRuntimeMode ?: userOverride.mode,
            mixedPort = when {
                userOverride.mixedPort != null -> userOverride.mixedPort
                subscriptionMixedPort != null -> null
                subscriptionUpdateViaProxy -> DEFAULT_MIXED_PORT
                else -> null
            },
            tproxyPort = when (tunMode) {
                // RootTproxy / RootTun 的 tether TPROXY：优先跟随活动配置的 tproxy-port。
                TunMode.RootTproxy -> effectiveRootTproxyPort
                    ?: ConfigGenerator.resolveRootTproxyPort(null, userOverride.tproxyPort)
                TunMode.RootTun -> if (tproxyForTether) {
                    effectiveRootTproxyPort ?: ConfigGenerator.resolveRootTproxyPort(null, userOverride.tproxyPort)
                } else userOverride.tproxyPort
                // VPN 与 RootEbpf 没有 App 侧 TPROXY 注入。
                TunMode.Vpn, TunMode.RootEbpf -> userOverride.tproxyPort
            },
            // RootTproxy 下**不**注入 routing-mark：Android Netd 用 fwmark 低 16 位编码 netId，
            // mihomo 若带 SO_MARK 会被解释为不存在的 netId，命中 legacy_system 表（无默认路由）
            // → mihomo 出站全部 `network unreachable`。iptables 改用 `-m owner --uid-owner 0`
            // 放行 mihomo（它以 root 运行），副作用是所有 root 进程都不经 TPROXY，但用户 app 正常代理
            routingMark = userOverride.routingMark,
            // tcp-concurrent：代理侧并发拨号，显著降低首包延迟，多客户端并发场景尤其有效。
            tcpConcurrent = userOverride.tcpConcurrent ?: true,
            // null 必须保持不写入 override.run.json：这样配置文件里的 find-process-mode 才不会
            // 被 App 的隐式 off 覆盖。Meta 设置选择 off/strict/always 时则显式覆盖配置文件。
            findProcessMode = userOverride.findProcessMode,
            dns = buildDnsOverride(tunMode, userOverride.dns),
            tun = buildTunOverride(
                context,
                tunMode,
                tunFd,
                userOverride.tun,
                subscriptionId,
                userOverride,
                effectiveRootTunDevice,
                useProfileTunDefaults,
                effectiveProfileTun,
                preferUserTunOverrides,
            ),
            listeners = effectiveProfileListeners ?: userOverride.listeners,
            profile = ProfileOverride(storeSelected = false, storeFakeIp = true),
        )
        // 原子写：mihomo 紧接着就以 --override-json 读它，半个 JSON 会让启动失败且难以定位
        val file = File(ConfigGenerator.getWorkDir(context), FILE_NAME)
        ProfileFileOps.writeAtomically(file, json.encodeToString(merged))
        return file
    }

    /**
     * RootTproxy 下把 mihomo DNS listener 强制监听在 `0.0.0.0:1053`，
     * iptables 的 `nat REDIRECT --to-ports 1053` 把系统 DNS 查询导到这里。
     * 保留用户的 `enhanced-mode`（fake-ip / redir-host 任选）和其他字段。
     */
    private fun buildDnsOverride(tunMode: TunMode, userDns: DnsOverride?): DnsOverride? {
        if (tunMode != TunMode.RootTproxy) return userDns
        val base = userDns ?: DnsOverride()
        return base.copy(
            enable = true,
            listen = "0.0.0.0:${RootTproxyApplier.DNS_PORT}",
        )
    }

    private fun buildTunOverride(
        context: Context,
        tunMode: TunMode,
        tunFd: Int,
        userTun: TunOverride?,
        subscriptionId: String?,
        userOverride: ConfigurationOverride,
        effectiveRootTunDevice: String?,
        useProfileTunDefaults: Boolean,
        effectiveProfileTun: JsonElement?,
        preferUserTunOverrides: Boolean,
    ): TunOverride {
        val profileDefaults = if (useProfileTunDefaults) defaultProfileTunOverride() else null
        val effectiveTun = mergeTunSettings(profileDefaults, userTun)
        // RootTproxy / RootEbpf：TUN 完全关闭，sing-tun 不初始化
        // （RootEbpf 的流量劫持由配置里的 ebpf listener 挂 TC 完成，与 TUN 无关）
        if (tunMode == TunMode.RootTproxy || tunMode == TunMode.RootEbpf) {
            return if (profileDefaults != null) {
                (effectiveTun ?: profileDefaults).copy(enable = false)
            } else {
                TunOverride(enable = false)
            }
        }

        val storage = PlatformStorage(context)
        if (tunMode == TunMode.Vpn) {
            val ipv6 = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
            val hijack = storage.getString(StorageKeys.VPN_DNS_HIJACKING, "true") == "true"
            return TunOverride(
                enable = true,
                stack = effectiveTun?.stack,
                fileDescriptor = tunFd,
                autoRoute = false,
                autoDetectInterface = false,
                inet6Address = if (ipv6) listOf("$VPN_GATEWAY6/$VPN_PREFIX6") else emptyList(),
                dnsHijack = buildList {
                    add(if (hijack) "0.0.0.0:53" else "$VPN_DNS:53")
                    if (ipv6) add(if (hijack) "[::]:53" else "[$VPN_DNS6]:53")
                },
                mtu = VPN_TUN_MTU,
            )
        }
        val isRootTun = tunMode == TunMode.RootTun
        if (isRootTun) {
            return buildRootTunOverride(
                context = context,
                storage = storage,
                userTun = userTun,
                subscriptionId = subscriptionId,
                userOverride = userOverride,
                effectiveRootTunDevice = effectiveRootTunDevice,
                profileTunElement = effectiveProfileTun,
                profileDefaults = profileDefaults,
                preferUserTunOverrides = preferUserTunOverrides,
            )
        }

        // 分应用代理：仅 RootTun 通过 mihomo include/exclude-package 实现；
        // VPN 由 VpnService.Builder.addAllowed/DisallowedApplication 管；
        // RootTproxy 走 iptables uid-owner（本函数早已 return）
        // Mishka 自身保持排除，避免死循环
        val include: List<String>?
        val exclude: List<String>?
        if (isRootTun) {
            val selfPkg = context.packageName
            val proxyMode = parseAppProxyMode(storage.getString(StorageKeys.APP_PROXY_MODE, AppProxyMode.AllowAll.name))
            val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
            when (proxyMode) {
                // 空列表时用无效包名占位，确保不代理任何应用
                AppProxyMode.AllowSelected -> {
                    val filtered = packages.filter { it != selfPkg }
                    include = if (filtered.isNotEmpty()) filtered else listOf("-")
                    exclude = null
                }

                AppProxyMode.DenySelected -> {
                    include = null
                    exclude = (packages + selfPkg).distinct()
                }

                AppProxyMode.AllowAll -> {
                    include = null
                    exclude = listOf(selfPkg)
                }
            }
        } else {
            include = null
            exclude = null
        }

        val ipv6Enabled = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
        // null 会被序列化省略；关闭时必须用空列表清除内核默认的 IPv6 地址。
        val inet6 = when {
            isRootTun && ipv6Enabled -> listOf("fdfe:dcba:9876::1/126")
            !isRootTun -> emptyList()
            else -> emptyList()
        }

        // ROOT TUN 的 auto_route 缺省铺满 0.0.0.0/0，会把 LAN 单播 + 224/4 组播吸进 mihomo，
        // 破坏同 LAN 设备发现 / P2P 直连（如妙享桌面）。注入私网 + 组播 route-exclude 留在物理网卡，
        // 与 VPN / TPROXY 的 LAN 放行对齐；仅 ROOT TUN 需要，用户显式设置优先。
        val routeExclude: List<String>? = when {
            !isRootTun -> effectiveTun?.routeExcludeAddress
            else -> effectiveTun?.routeExcludeAddress ?: buildList {
                addAll(IptablesIntranet.V4)
                if (ipv6Enabled) addAll(IptablesIntranet.V6)
            }
        }

        val device = if (isRootTun) {
            effectiveRootTunDevice?.takeIf { it.isNotBlank() }
                ?: ConfigGenerator.resolveRootTunDevice(context, userOverride, subscriptionId, storage)
        } else {
            effectiveTun?.device
        }

        // sing-tun userspace TUN 性能：mtu=9000 + gso + gso-max-size=65535 让大包聚合，
        // 减少每包 read syscall；仅 ROOT TUN 注入 GSO（VPN fd 由 VpnService 创建无 vnet header）。
        // 用户 override 的同名字段优先级最高，允许极端 ROM 下手动回退。
        // VPN 模式 MTU 必须与 VpnService.Builder.setMtu 同步：sing-tun fd 模式给 gvisor fdbased.New
        // 用 cfg.Tun.MTU 设 endpoint 缓冲，0 时所有 read 失败 → VPN 表面"延迟正常但流量不通"。
        val jumbo = storage.getString(StorageKeys.ROOT_TUN_JUMBO_MTU, "true") == "true"
        val defaultMtu: Int = if (isRootTun && !jumbo) 1500 else VPN_TUN_MTU
        val rootTunGso: Boolean? = if (isRootTun) jumbo else null
        val rootTunGsoMax: Int? = if (isRootTun && jumbo) 65535 else null

        return TunOverride(
            enable = true,
            device = device,
            stack = effectiveTun?.stack,
            fileDescriptor = tunFd.takeIf { it >= 0 && !isRootTun },
            autoRoute = isRootTun,
            strictRoute = effectiveTun?.strictRoute,
            autoRedirect = effectiveTun?.autoRedirect,
            autoDetectInterface = isRootTun,
            routeExcludeAddress = routeExclude,
            inet6Address = inet6,
            dnsHijack = effectiveTun?.dnsHijack ?: listOf("0.0.0.0:53"),
            includePackage = include,
            excludePackage = exclude,
            iproute2TableIndex = if (isRootTun) ROOT_TUN_TABLE else null,
            iproute2RuleIndex = if (isRootTun) ROOT_TUN_RULE_INDEX else null,
            mtu = effectiveTun?.mtu ?: defaultMtu,
            gso = effectiveTun?.gso ?: rootTunGso,
            gsoMaxSize = effectiveTun?.gsoMaxSize ?: rootTunGsoMax,
        )
    }

    /**
     * Root TUN uses the effective profile tun mapping as its source of truth. App-managed values
     * only fill profile gaps, except for an explicit app edit on an encrypted/transformed profile
     * (those source values cannot be edited in place or may be rewritten by the transform).
     */
    private fun buildRootTunOverride(
        context: Context,
        storage: PlatformStorage,
        userTun: TunOverride?,
        subscriptionId: String?,
        userOverride: ConfigurationOverride,
        effectiveRootTunDevice: String?,
        profileTunElement: JsonElement?,
        profileDefaults: TunOverride?,
        preferUserTunOverrides: Boolean,
    ): TunOverride {
        val profileTun = profileTunElement?.let {
            runCatching { json.decodeFromJsonElement<TunOverride>(it) }.getOrNull()
        } ?: profileDefaults

        fun <T> resolve(profileValue: T?, appValue: T?, fallback: T? = null): T? = when {
            preferUserTunOverrides && appValue != null -> appValue
            profileValue != null -> profileValue
            appValue != null -> appValue
            else -> fallback
        }

        val jumboFallback = storage.getString(StorageKeys.ROOT_TUN_JUMBO_MTU, "true") == "true"
        val defaultMtu = if (jumboFallback) VPN_TUN_MTU else 1500
        val defaultGso = jumboFallback
        val resolvedGso = resolve(profileTun?.gso, userTun?.gso, defaultGso)
        val defaultGsoMaxSize = if (resolvedGso == true) 65535 else null

        val ipv6Enabled = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
        val defaultInet6 = if (ipv6Enabled) listOf("fdfe:dcba:9876::1/126") else emptyList()
        val defaultRouteExclude = buildList {
            addAll(IptablesIntranet.V4)
            if (ipv6Enabled) addAll(IptablesIntranet.V6)
        }

        val profileHasPackageFilter = profileTun?.includePackage != null || profileTun?.excludePackage != null
        val appHasPackageFilter = userTun?.includePackage != null || userTun?.excludePackage != null
        val (generatedInclude, generatedExclude) = if (!profileHasPackageFilter && !appHasPackageFilter) {
            appProxyFallbackFilter(context, storage)
        } else {
            null to null
        }

        val device = effectiveRootTunDevice?.takeIf { it.isNotBlank() }
            ?: ConfigGenerator.resolveRootTunDevice(context, userOverride, subscriptionId, storage)

        return TunOverride(
            // Root TUN always needs an enabled tun device, even if the original profile was disabled.
            enable = true,
            device = device,
            stack = resolve(profileTun?.stack, userTun?.stack),
            // ROOT creates its own tun interface; VPN-only file-descriptor settings are not reused.
            autoRoute = resolve(profileTun?.autoRoute, userTun?.autoRoute, true),
            strictRoute = resolve(profileTun?.strictRoute, userTun?.strictRoute),
            autoRedirect = resolve(profileTun?.autoRedirect, userTun?.autoRedirect),
            autoDetectInterface = resolve(profileTun?.autoDetectInterface, userTun?.autoDetectInterface, true),
            routeExcludeAddress = resolve(
                profileTun?.routeExcludeAddress,
                userTun?.routeExcludeAddress,
                defaultRouteExclude,
            ),
            inet6Address = resolve(profileTun?.inet6Address, userTun?.inet6Address, defaultInet6),
            dnsHijack = resolve(profileTun?.dnsHijack, userTun?.dnsHijack, listOf("0.0.0.0:53")),
            includePackage = resolve(profileTun?.includePackage, userTun?.includePackage, generatedInclude),
            excludePackage = resolve(profileTun?.excludePackage, userTun?.excludePackage, generatedExclude),
            iproute2TableIndex = resolve(profileTun?.iproute2TableIndex, userTun?.iproute2TableIndex, ROOT_TUN_TABLE),
            iproute2RuleIndex = resolve(profileTun?.iproute2RuleIndex, userTun?.iproute2RuleIndex, ROOT_TUN_RULE_INDEX),
            mtu = resolve(profileTun?.mtu, userTun?.mtu, defaultMtu),
            gso = resolvedGso,
            gsoMaxSize = resolve(profileTun?.gsoMaxSize, userTun?.gsoMaxSize, defaultGsoMaxSize),
        )
    }

    private fun defaultProfileTunOverride() = TunOverride(
        enable = true,
        device = ActiveProfileRuntimeConfig.DEFAULT_TUN_DEVICE,
        stack = "system",
        dnsHijack = listOf("any:53", "tcp://any:53"),
        mtu = VPN_TUN_MTU,
        autoRoute = true,
        strictRoute = true,
        autoRedirect = true,
        autoDetectInterface = true,
    )

    /** App override values win over generated profile defaults; null fields inherit the defaults. */
    private fun mergeTunSettings(defaults: TunOverride?, user: TunOverride?): TunOverride? {
        if (defaults == null) return user
        if (user == null) return defaults
        return defaults.copy(
            enable = user.enable ?: defaults.enable,
            device = user.device ?: defaults.device,
            stack = user.stack ?: defaults.stack,
            fileDescriptor = user.fileDescriptor ?: defaults.fileDescriptor,
            autoRoute = user.autoRoute ?: defaults.autoRoute,
            strictRoute = user.strictRoute ?: defaults.strictRoute,
            autoRedirect = user.autoRedirect ?: defaults.autoRedirect,
            autoDetectInterface = user.autoDetectInterface ?: defaults.autoDetectInterface,
            routeExcludeAddress = user.routeExcludeAddress ?: defaults.routeExcludeAddress,
            inet6Address = user.inet6Address ?: defaults.inet6Address,
            dnsHijack = user.dnsHijack ?: defaults.dnsHijack,
            includePackage = user.includePackage ?: defaults.includePackage,
            excludePackage = user.excludePackage ?: defaults.excludePackage,
            iproute2TableIndex = user.iproute2TableIndex ?: defaults.iproute2TableIndex,
            iproute2RuleIndex = user.iproute2RuleIndex ?: defaults.iproute2RuleIndex,
            mtu = user.mtu ?: defaults.mtu,
            gso = user.gso ?: defaults.gso,
            gsoMaxSize = user.gsoMaxSize ?: defaults.gsoMaxSize,
        )
    }

    /**
     * 分应用代理页（APP_PROXY_*）的兜底名单，返回 (include, exclude)。TUN 与 Tproxy 共用，保证两种模式语义一致。
     */
    private fun appProxyFallbackFilter(context: Context, storage: PlatformStorage): Pair<List<String>?, List<String>?> {
        val selfPkg = context.packageName
        val proxyMode = parseAppProxyMode(storage.getString(StorageKeys.APP_PROXY_MODE, AppProxyMode.AllowAll.name))
        val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
        return when (proxyMode) {
            // 空列表时用无效包名占位，确保不代理任何应用
            AppProxyMode.AllowSelected -> {
                val filtered = packages.filter { it != selfPkg }
                (if (filtered.isNotEmpty()) filtered else listOf("-")) to null
            }
            AppProxyMode.DenySelected -> null to (packages + selfPkg).distinct()
            AppProxyMode.AllowAll -> null to listOf(selfPkg)
        }
    }

    /**
     * Tproxy 分应用名单。来源与 RootTun 完全一致：profile 的 tun 段 → 用户 override 的 tun → 分应用代理页兜底；
     * 区别只在落点：RootTun 交给 mihomo include/exclude-package，Tproxy 由 iptables uid-owner 实现。
     * [profileTunElement] 为 profile 的 tun 段（JSON）；传 null 时只看分应用代理页（与旧版行为一致）。
     */
    internal fun resolveTproxyAppFilter(
        context: Context,
        userTun: TunOverride?,
        profileTunElement: JsonElement?,
        preferUserTunOverrides: Boolean,
    ): TproxyAppFilter {
        val profileTun = profileTunElement?.let {
            runCatching { json.decodeFromJsonElement<TunOverride>(it) }.getOrNull()
        }
        val hasTunFilter = profileTun?.includePackage != null || profileTun?.excludePackage != null ||
            userTun?.includePackage != null || userTun?.excludePackage != null
        val (include, exclude) = if (hasTunFilter) {
            pickTunList(preferUserTunOverrides, profileTun?.includePackage, userTun?.includePackage) to
                pickTunList(preferUserTunOverrides, profileTun?.excludePackage, userTun?.excludePackage)
        } else {
            appProxyFallbackFilter(context, PlatformStorage(context))
        }
        val users = profileTunElement?.let { readAndroidUsers(it) }.orEmpty()
        return TproxyAppFilter.pick(include, exclude, users)
    }

    private fun pickTunList(preferUser: Boolean, profileValue: List<String>?, appValue: List<String>?): List<String>? =
        when {
            preferUser && appValue != null -> appValue
            profileValue != null -> profileValue
            appValue != null -> appValue
            else -> null
        }

    /** profile tun 段里的 include-android-user（数组或单个数字），用于把 UID 展开到其它安卓用户。 */
    private fun readAndroidUsers(tun: JsonElement): List<Int> {
        val field = (tun as? JsonObject)?.get("include-android-user") ?: return emptyList()
        val items = (field as? JsonArray)?.toList() ?: listOf(field)
        return items.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it >= 0 }.distinct()
    }

    private fun parseAppProxyMode(name: String): AppProxyMode =
        runCatching { AppProxyMode.valueOf(name) }.getOrDefault(AppProxyMode.AllowAll)
}
