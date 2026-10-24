package top.yukonga.mishka.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.data.api.RuleLatencyTester
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.domain.model.ConnectionInfo
import top.yukonga.mishka.domain.model.MihomoConfig
import top.yukonga.mishka.domain.model.ProvidersResponse
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.model.SubscriptionInfo
import top.yukonga.mishka.domain.model.TunOverride
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.platform.NetworkAddressType
import top.yukonga.mishka.platform.PlatformSystemInfo
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.service.ActiveProfileRuntimeConfig
import top.yukonga.mishka.util.FormatUtils
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 低频状态：mihomo 运行状态、配置、代理组、延迟、错误等；改变频率与生命周期事件相当 */
@Immutable
data class HomeUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val mode: String = "--",
    val tunStack: String = "",
    val tunMode: TunMode = TunMode.Vpn,
    val ipv6: Boolean = false,
    val config: MihomoConfig? = null,
    val subscription: SubscriptionInfo? = null,
    val providerTraffic: ImmutableList<ProviderTrafficInfo> = persistentListOf(),
    val isProviderTrafficLoading: Boolean = false,
    val providerTrafficLoadFailed: Boolean = false,
    val latencyBaidu: Int = -1,
    val latencyCloudflare: Int = -1,
    val latencyGoogle: Int = -1,
    val isTestingLatency: Boolean = false,
    // false = 本轮退回了组拨测、未经规则引擎，UI 据此标注
    val latencyViaRules: Boolean = true,
    val version: String = "",
    val errorMessage: String = "",
    val needsVpnPermission: Boolean = false,
)

@Immutable
data class ProviderTrafficInfo(
    val id: String,
    val name: String,
    val nodeCount: Int,
    val updatedAt: String,
    // subscription-userinfo 缺失时 false：UI 仅隐藏流量面板，不整卡过滤
    val hasTraffic: Boolean,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
)

// mihomo 的 "default" 特殊 provider（proxies: 段运行时聚合）的 vehicleType，非订阅集合，展示与更新都排除
private const val VEHICLE_TYPE_COMPATIBLE = "Compatible"

/**
 * 流量历史窗口：最近 [HomeViewModel.TRAFFIC_HISTORY_CAPACITY] 个采样点的原始字节速率，用于绘制折线图。
 * [seq] 单调递增，UI 侧既拿它作滑入动画的 key 也作动画目标值——比对 list 内容无法区分「新点与上一点等值」。
 */
@Immutable
data class TrafficHistory(
    val up: ImmutableList<Long> = persistentListOf(),
    val down: ImmutableList<Long> = persistentListOf(),
    val seq: Int = 0,
)

/** 高频流量快照：每 100–500ms 更新，独立 Flow 隔离重组 */
@Immutable
data class SpeedSnapshot(
    val uploadSpeed: String = "-- B/s",
    val downloadSpeed: String = "-- B/s",
    val history: TrafficHistory = TrafficHistory(),
)

/** 主页连通性体检的三个探针；URL 一律用 http 明文，避免 TLS 握手把测量口径拉长 */
enum class LatencyProbe(val url: String) {
    Baidu("http://www.baidu.com"),
    Cloudflare("http://www.cloudflare.com/cdn-cgi/trace"),
    Google("http://www.google.com/generate_204"),
}

/** 高频内存快照 */
@Immutable
data class MemorySnapshot(
    val ramUsage: String = "-- MB",
    val ramTotal: String = "-- MB",
)

/**
 * 单条连接的瞬时速率，由相邻两次 `/connections` 快照的累计字节差分得出。
 * mihomo 只给累计量，不给速率——长连接的累计值会一直很大，据此排序看不出「此刻谁在跑」。
 */
@Immutable
data class ConnectionRate(
    val id: String,
    val host: String,
    val node: String,
    val uploadRate: Long,
    val downloadRate: Long,
)

/** 系统信息快照：网卡 + CPU，2s 一次 */
@Immutable
data class SystemInfoSnapshot(
    val localIp: String = "--",
    val interfaceName: String = "--",
    val addressType: NetworkAddressType = NetworkAddressType.Unknown,
    val cpuUsage: String = "--%",
)

class HomeViewModel(
    private val serviceController: ProxyServiceController,
    private val overrideStore: OverrideJsonStore,
    private val connectionManager: MihomoConnectionManager,
    private val latencyTester: RuleLatencyTester,
    private val systemInfo: PlatformSystemInfo,
    private val context: Context,
    private val getActiveSubscriptionId: () -> String? = { null },
    private val activeSubscription: StateFlow<Subscription?> = MutableStateFlow(null).asStateFlow(),
    private val onLiveProviderInfo: (subscriptionId: String?, info: SubscriptionInfo?) -> Unit = { _, _ -> },
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(mode = persistedMode()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _speedState = MutableStateFlow(SpeedSnapshot())
    val speedState: StateFlow<SpeedSnapshot> = _speedState.asStateFlow()

    private val _memoryState = MutableStateFlow(MemorySnapshot())
    val memoryState: StateFlow<MemorySnapshot> = _memoryState.asStateFlow()

    private val _systemInfoState = MutableStateFlow(SystemInfoSnapshot())
    val systemInfoState: StateFlow<SystemInfoSnapshot> = _systemInfoState.asStateFlow()

    // 秒；-1 表示尚未启动/已重置（UI 层格式化时转为空串）
    private val _uptimeState = MutableStateFlow(-1L)
    val uptimeState: StateFlow<Long> = _uptimeState.asStateFlow()

    // 折线图的滑动窗口缓冲，仅在 viewModelScope(Main) 的 traffic collect 内访问，无需加锁
    private val upHistory = ArrayDeque<Long>(TRAFFIC_HISTORY_CAPACITY)
    private val downHistory = ArrayDeque<Long>(TRAFFIC_HISTORY_CAPACITY)
    private var trafficSeq = 0

    // null = 首轮差分未完成，空表 = 确实无活跃连接；UI 据此区分加载与空态
    private val _topConnectionRates = MutableStateFlow<ImmutableList<ConnectionRate>?>(null)
    val topConnectionRates: StateFlow<ImmutableList<ConnectionRate>?> = _topConnectionRates.asStateFlow()

    // 上一次 /connections 快照：id → [累计上传, 累计下载]，仅在 connectionRateJob 内访问
    private var prevConnectionBytes = emptyMap<String, LongArray>()
    private var prevConnectionAtMillis = 0L

    private var repository: MihomoRepository? = null
    private var latencyJob: Job? = null
    // 运行模式即时切换：过渡态等状态落定后再打 PATCH，期间又点一次就取消上一次
    private var modeApplyJob: Job? = null
    private var connectionRateJob: Job? = null
    private var trafficJob: Job? = null
    private var memoryJob: Job? = null
    private var systemInfoJob: Job? = null
    private var runtimeConfigJob: Job? = null
    private var initialLoadJob: Job? = null
    private var providerTrafficJob: Job? = null
    private var providerTrafficRequestId = 0L
    private var repositorySubscriptionId: String? = null
    private var startTime: Long = 0
    private var uptimeJob: Job? = null
    private var mihomoPid: Int = -1
    private val profileTunWriteMutex = Mutex()
    private data class TunConfigUpdateOutcome(val encrypted: Boolean = false, val failed: Boolean = false)

    /** UI 是否在前台，由 MainActivity 的 onStart/onStop 驱动，门控各轮询循环 */
    private val uiVisible = MutableStateFlow(true)

    // 已弹过 toast 的错误消息，用于抑制同一失败的重复提示；回到 Stopped 时清空
    private var lastErrorToast: String? = null

    init {
        // 状态机仅维护 UI 状态字段（isStarting / isRunning / startTime / mihomoPid / errorMessage）
        // mihomo 客户端实例由 connectionManager 统一持有，HomeViewModel 不自建
        viewModelScope.launch {
            serviceController.status.collect { status ->
                when (status.state) {
                    ProxyState.Starting -> {
                        _uiState.value = _uiState.value.copy(
                            isStarting = true,
                            isStopping = false,
                            tunMode = status.tunMode,
                            subscription = activeSubscriptionInfo(),
                        )
                    }

                    ProxyState.Running -> {
                        _uiState.value = _uiState.value.copy(
                            isStarting = false,
                            isRunning = true,
                            tunMode = status.tunMode,
                            subscription = activeSubscriptionInfo(),
                        )
                        startTime = if (status.startTime > 0) status.startTime else Clock.System.now().toEpochMilliseconds()
                        mihomoPid = status.mihomoPid
                    }

                    ProxyState.Stopping -> {
                        _uiState.value = _uiState.value.copy(isRunning = false, isStopping = true)
                    }

                    ProxyState.Stopped -> {
                        _uiState.value = HomeUiState(mode = persistedMode())
                        lastErrorToast = null
                        resetHotStates()
                    }

                    ProxyState.Error -> {
                        // errorMessage 不在首页渲染，Error 与 Stopped 视觉上无差别，失败必须弹出来。
                        // 跳过发布方已提示过的，同一条也只弹一次（Error 可能重复 emit）
                        val message = status.errorMessage
                        if (message.isNotBlank() && !status.errorNotified && message != lastErrorToast) {
                            lastErrorToast = message
                            showToast(message, long = true)
                        }
                        _uiState.value = HomeUiState(errorMessage = message, mode = persistedMode())
                        resetHotStates()
                    }
                }
            }
        }
        // 订阅共享 connectionManager.repository：非 null 表示 mihomo Running，触发数据收集；
        // null 表示已停止，cancel 流并重置数据。close 由 manager 负责，此处不调用。
        viewModelScope.launch {
            connectionManager.repository.collect { repo ->
                repository = repo
                if (repo != null) {
                    connectToMihomo()
                } else {
                    disconnectStreams()
                }
            }
        }
        // activeSubscription 变化的两个驱动源：① 用户切 active 订阅 ② Repository merge 进
        // mihomo live provider 后 emit 新值。两种情况主页流量栏都需要即时刷新。
        viewModelScope.launch {
            activeSubscription.collect {
                if (_uiState.value.isRunning || _uiState.value.isStarting) {
                    _uiState.value = _uiState.value.copy(subscription = activeSubscriptionInfo())
                }
                if (repository != null && getActiveSubscriptionId() != repositorySubscriptionId) {
                    clearProviderTraffic()
                }
            }
        }
    }

    private fun connectToMihomo() {
        val repo = repository ?: return
        val subscriptionId = getActiveSubscriptionId()
        repositorySubscriptionId = subscriptionId
        // 立即用 DB 缓存填充流量栏；provider 流量随后由独立请求覆盖。
        _uiState.value = _uiState.value.copy(subscription = activeSubscriptionInfo())
        startTrafficCollection()
        startMemoryCollection()
        startSystemInfoCollection()
        startRuntimeConfigRefresh()
        startUptimeCounter()
        initialLoadJob?.cancel()
        initialLoadJob = viewModelScope.launch {
            loadConfig(repo)
            if (repository === repo) testLatency()
        }
        refreshProviderTraffic(repo, subscriptionId)
    }

    private suspend fun loadConfig(repo: MihomoRepository) {
        repo.getConfig().onSuccess { config ->
            if (repository !== repo) return@onSuccess
            _uiState.update {
                it.copy(
                    isRunning = true,
                    mode = config.mode,
                    tunStack = config.tun?.stack ?: "",
                    ipv6 = config.ipv6,
                    config = config,
                )
            }
        }
        repo.getVersion().onSuccess { version ->
            if (repository !== repo) return@onSuccess
            _uiState.update { it.copy(version = version.version) }
        }
    }

    /**
     * Provider 流量既用于主页汇总，也供底部弹窗逐项展示。每次连接和打开弹窗时刷新；
     * 请求 token 与 repository identity 双重校验，避免旧订阅的 HTTP 响应覆盖新订阅。
     */
    fun refreshProviderTraffic() {
        val repo = repository ?: return
        val subscriptionId = repositorySubscriptionId
        if (getActiveSubscriptionId() != subscriptionId) return
        refreshProviderTraffic(repo, subscriptionId)
    }

    private fun refreshProviderTraffic(repo: MihomoRepository, subscriptionId: String?) {
        providerTrafficJob?.cancel()
        val requestId = ++providerTrafficRequestId
        setProviderTrafficLoading(repo, subscriptionId, requestId)
        providerTrafficJob = viewModelScope.launch {
            loadProviderTrafficSnapshot(repo, subscriptionId, requestId)
        }
    }

    /**
     * 更新全部 provider：mihomo 仅在 provider 重新拉订阅时刷新 subscription-userinfo，
     * 纯 GET 读到的永远是上次更新的旧快照。逐个 PUT 触发更新（单个失败不中断，
     * 该 provider 保持旧数据），全部完成后统一重读快照并把聚合推回 Repository。
     */
    fun updateAllProviders() {
        val repo = repository ?: return
        val subscriptionId = repositorySubscriptionId
        if (getActiveSubscriptionId() != subscriptionId) return
        providerTrafficJob?.cancel()
        val requestId = ++providerTrafficRequestId
        setProviderTrafficLoading(repo, subscriptionId, requestId)
        providerTrafficJob = viewModelScope.launch {
            val names = repo.getProviders().getOrNull()?.providers.orEmpty()
                .filterValues { !it.vehicleType.equals(VEHICLE_TYPE_COMPATIBLE, ignoreCase = true) }
                .map { (fallbackName, provider) -> provider.name.ifBlank { fallbackName } }
            if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) return@launch
            // 各 provider 拉取彼此独立，串行会把多源订阅的刷新耗时叠成总和
            names.map { name -> async { repo.updateProvider(name) } }.awaitAll()
            if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) return@launch
            loadProviderTrafficSnapshot(repo, subscriptionId, requestId)
        }
    }

    private fun setProviderTrafficLoading(repo: MihomoRepository, subscriptionId: String?, requestId: Long) {
        _uiState.update { state ->
            if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) state else state.copy(
                isProviderTrafficLoading = true,
                providerTrafficLoadFailed = false,
            )
        }
    }

    private suspend fun loadProviderTrafficSnapshot(
        repo: MihomoRepository,
        subscriptionId: String?,
        requestId: Long,
    ) {
        val result = repo.getProviders()
        if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) return
        result.onSuccess { providers ->
            _uiState.update { state ->
                if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) state else state.copy(
                    providerTraffic = providerTrafficInfo(providers),
                    isProviderTrafficLoading = false,
                    providerTrafficLoadFailed = false,
                )
            }
            if (isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) {
                onLiveProviderInfo(subscriptionId, aggregateProviderInfo(providers))
            }
        }.onFailure {
            _uiState.update { state ->
                if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) state else state.copy(
                    isProviderTrafficLoading = false,
                    providerTrafficLoadFailed = true,
                )
            }
        }
    }

    private fun isCurrentProviderTrafficRequest(
        repo: MihomoRepository,
        subscriptionId: String?,
        requestId: Long,
    ): Boolean = repository === repo &&
            repositorySubscriptionId == subscriptionId &&
            getActiveSubscriptionId() == subscriptionId &&
            providerTrafficRequestId == requestId

    private fun clearProviderTraffic() {
        providerTrafficJob?.cancel()
        providerTrafficRequestId++
        _uiState.update {
            it.copy(
                providerTraffic = persistentListOf(),
                isProviderTrafficLoading = false,
                providerTrafficLoadFailed = false,
            )
        }
    }

    /**
     * 多订阅源（yaml 含多个 proxy-provider）时聚合所有有 subscription-userinfo header
     * 的 provider：流量求和、过期时间取最近。单源场景退化为单值；全部无 userinfo 返回 null
     * 让 Repository 走 DB 回退（File 类型订阅或服务端不返回 header 时）。
     */
    private fun aggregateProviderInfo(providers: ProvidersResponse): SubscriptionInfo? {
        val valid = providers.providers.values.mapNotNull { info ->
            info.subscriptionInfo?.takeIf { it.Total > 0 }
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first()
        return SubscriptionInfo(
            Upload = valid.sumOf { it.Upload },
            Download = valid.sumOf { it.Download },
            Total = valid.sumOf { it.Total },
            Expire = valid.filter { it.Expire > 0 }.minOfOrNull { it.Expire } ?: 0,
        )
    }

    private fun providerTrafficInfo(providers: ProvidersResponse): ImmutableList<ProviderTrafficInfo> {
        val nonCompatible = providers.providers.filterValues {
            !it.vehicleType.equals(VEHICLE_TYPE_COMPATIBLE, ignoreCase = true)
        }
        val sub = activeSubscription.value
        // 单 provider 场景且内核未读到其 subscriptionInfo 时，以主订阅 DB 流量兜底（模板订阅提取自该唯一 provider）
        val fallbackSubInfo = if (nonCompatible.size == 1 && sub != null && sub.total > 0) sub else null
        return nonCompatible
            .map { (fallbackName, provider) ->
                val info = provider.subscriptionInfo
                val hasTraffic = info != null || fallbackSubInfo != null
                val upload = info?.Upload ?: fallbackSubInfo?.upload ?: 0
                val download = info?.Download ?: fallbackSubInfo?.download ?: 0
                val total = info?.Total ?: fallbackSubInfo?.total ?: 0
                val expire = info?.Expire ?: fallbackSubInfo?.expire ?: 0
                ProviderTrafficInfo(
                    id = fallbackName,
                    name = provider.name.ifBlank { fallbackName },
                    nodeCount = provider.proxies.size,
                    updatedAt = provider.updatedAt,
                    hasTraffic = hasTraffic,
                    upload = upload,
                    download = download,
                    total = total,
                    expire = expire,
                )
            }
            .sortedBy { it.name.lowercase() }
            .toPersistentList()
    }

    /**
     * 当前活跃订阅的视图流量信息。Repository 已合并 mihomo runtime live data 到 active
     * Subscription，本函数只做 model 转换。
     *
     * `total<=0`（不限量套餐，或 header 根本没给 total）时配额语义不成立：这里返回 null，
     * UI 侧据此退回 "--"、不画用量水印。各卡片的处理都以本注释为准，别再各写一份。
     */
    private fun activeSubscriptionInfo(): SubscriptionInfo? {
        val sub = activeSubscription.value ?: return null
        if (sub.total <= 0) return null
        return SubscriptionInfo(
            Upload = sub.upload,
            Download = sub.download,
            Total = sub.total,
            Expire = sub.expire,
        )
    }

    private fun startTrafficCollection() {
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch {
            repository?.trafficFlow()
                ?.collect { traffic ->
                    upHistory.addLast(traffic.up)
                    downHistory.addLast(traffic.down)
                    while (upHistory.size > TRAFFIC_HISTORY_CAPACITY) upHistory.removeFirst()
                    while (downHistory.size > TRAFFIC_HISTORY_CAPACITY) downHistory.removeFirst()
                    _speedState.value = SpeedSnapshot(
                        uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                        downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                        history = TrafficHistory(
                            up = upHistory.toPersistentList(),
                            down = downHistory.toPersistentList(),
                            seq = ++trafficSeq,
                        ),
                    )
                    if (!_uiState.value.isRunning) {
                        _uiState.value = _uiState.value.copy(isRunning = true)
                    }
                }
        }
    }

    /**
     * 订阅 `/connections` 并差分出各连接的瞬时速率。**只在速度卡详情打开期间调用**——每次 collect 新建一条 WS，
     * 而这条流每秒推全量连接列表，常驻订阅代价不小；UI 关闭详情即调 [stopConnectionRateTracking]。
     */
    fun startConnectionRateTracking() {
        val repo = repository ?: return
        if (connectionRateJob?.isActive == true) return
        prevConnectionBytes = emptyMap()
        prevConnectionAtMillis = 0L
        _topConnectionRates.value = null
        connectionRateJob = viewModelScope.launch {
            repo.connectionsFlow()
                .collect { response ->
                    if (repository !== repo) return@collect
                    updateConnectionRates(response.connections)
                }
        }
    }

    fun stopConnectionRateTracking() {
        connectionRateJob?.cancel()
        connectionRateJob = null
        prevConnectionBytes = emptyMap()
        prevConnectionAtMillis = 0L
        _topConnectionRates.value = null
    }

    private fun updateConnectionRates(connections: List<ConnectionInfo>) {
        val now = Clock.System.now().toEpochMilliseconds()
        val snapshot = connections.associate { it.id to longArrayOf(it.upload, it.download) }
        val elapsedMillis = now - prevConnectionAtMillis
        // 首轮只留基准：此时每条连接的累计量都还没有对照，全当成速率会把长连接的历史总量算成瞬时值
        if (prevConnectionAtMillis == 0L || elapsedMillis <= 0) {
            prevConnectionBytes = snapshot
            prevConnectionAtMillis = now
            return
        }

        val perSecond = elapsedMillis / 1000.0
        val rates = connections.mapNotNull { conn ->
            val prev = prevConnectionBytes[conn.id]
            // prev 缺失说明这条连接是上一轮之后新建的，其累计量就是这段间隔内传的
            val upDelta = (conn.upload - (prev?.get(0) ?: 0L)).coerceAtLeast(0L)
            val downDelta = (conn.download - (prev?.get(1) ?: 0L)).coerceAtLeast(0L)
            if (upDelta == 0L && downDelta == 0L) return@mapNotNull null
            ConnectionRate(
                id = conn.id,
                host = conn.metadata.host.ifEmpty {
                    "${conn.metadata.destinationIP}:${conn.metadata.destinationPort}"
                },
                // mihomo 的 chains 由内向外，首项即最终出站节点
                node = conn.chains.firstOrNull().orEmpty(),
                uploadRate = (upDelta / perSecond).toLong(),
                downloadRate = (downDelta / perSecond).toLong(),
            )
        }

        prevConnectionBytes = snapshot
        prevConnectionAtMillis = now
        _topConnectionRates.value = rates
            .sortedByDescending { it.uploadRate + it.downloadRate }
            .take(TOP_CONNECTION_COUNT)
            .toPersistentList()
    }

    private fun startMemoryCollection() {
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            repository?.memoryFlow()
                ?.collect { memory ->
                    _memoryState.value = MemorySnapshot(
                        ramUsage = FormatUtils.formatBytes(memory.inuse),
                        ramTotal = if (memory.oslimit > 0) FormatUtils.formatBytes(memory.oslimit) else "-- MB",
                    )
                }
        }
    }

    /**
     * 轮询骨架：UI 不可见时挂在 [uiVisible] 上。这些循环由 viewModelScope 驱动，
     * 不感知生命周期——不门控就会在后台持续读 /proc、打本地 HTTP。
     */
    private fun pollWhileVisible(interval: Duration, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            while (true) {
                uiVisible.first { it }
                block()
                delay(interval)
            }
        }

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = pollWhileVisible(1000.milliseconds) {
            _uptimeState.value = (Clock.System.now().toEpochMilliseconds() - startTime) / 1000
        }
    }

    private fun startSystemInfoCollection() {
        systemInfoJob?.cancel()
        systemInfoJob = pollWhileVisible(2000.milliseconds) {
            val tunMode = serviceController.status.value.tunMode
            val tunDevice = _uiState.value.config?.tun?.device.orEmpty()
            // NetworkInterface 枚举与 /proc/<pid>/stat 都是阻塞调用
            val snapshot = withContext(Dispatchers.IO) {
                val networkInfo = systemInfo.getNetworkInfo(tunMode, tunDevice)
                val cpu = systemInfo.getCpuUsage(mihomoPid)
                SystemInfoSnapshot(
                    localIp = networkInfo.localIp,
                    interfaceName = networkInfo.interfaceName,
                    addressType = networkInfo.addressType,
                    cpuUsage = if (cpu >= 0) "${cpu.toInt()}%" else "--%",
                )
            }
            _systemInfoState.value = snapshot
        }
    }

    private fun startRuntimeConfigRefresh() {
        runtimeConfigJob?.cancel()
        runtimeConfigJob = pollWhileVisible(2000.milliseconds) { refreshRuntimeConfig() }
    }

    private suspend fun refreshRuntimeConfig() {
        repository?.getConfig()?.onSuccess { config ->
            val current = _uiState.value
            if (!current.isRunning || current.config == config) return@onSuccess
            _uiState.value = current.copy(
                mode = config.mode,
                tunStack = config.tun?.stack ?: "",
                ipv6 = config.ipv6,
                config = config,
            )
        }
    }

    private fun resetHotStates() {
        upHistory.clear()
        downHistory.clear()
        trafficSeq = 0
        _speedState.value = SpeedSnapshot()
        _memoryState.value = MemorySnapshot()
        _systemInfoState.value = SystemInfoSnapshot()
        _uptimeState.value = -1L
    }

    fun startProxy() {
        if (!serviceController.hasVpnPermission()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            serviceController.requestVpnPermission()
            return
        }
        serviceController.start(getActiveSubscriptionId())
    }

    fun stopProxy() {
        serviceController.stop()
    }

    fun restartProxy() {
        serviceController.restart(getActiveSubscriptionId())
    }

    /**
     * active 订阅变更后调用（切换或删除）：把运行中的代理切到新 active 订阅。
     * 删除路径必须等 DB 与文件落定后才调，见 `SubscriptionViewModel.removeSubscription`。
     * 重启时机的决策（权威状态、过渡态挂起）收在 [ProxyServiceController.restartWhenReady]。
     */
    fun onActiveSubscriptionChanged() {
        // 删光最后一条订阅时没有新配置可切，restart 只会在启动校验里失败成 Error 态
        if (!serviceController.hasStartableSubscription()) {
            serviceController.cancelPendingRestart()
            val state = serviceController.status.value.state
            if (state == ProxyState.Running || state == ProxyState.Starting) stopProxy()
            return
        }
        serviceController.restartWhenReady(getActiveSubscriptionId())
    }

    /**
     * 停止态展示的 mode：`/configs` 只在 Running 时有值，停了就回退 override 里「下次启动生效」
     * 的那个。override 未显式设置时生效值来自订阅 yaml、app 侧不可见，仍是 "--"。
     */
    private fun persistedMode(): String = overrideStore.load().mode?.takeIf { it.isNotBlank() } ?: "--"

    /**
     * 切换运行模式（规则 / 全局 / 直连）。
     *
     * 运行中走一次 `PATCH /configs` 即时生效——对齐 mihomo_box 模块的「运行模式」：内核不重启、
     * 启动链不重跑、代理不中断；随后在后台断开存量连接，老长连接会按旧模式继续路由几分钟，
     * 不主动断开的话用户体感就是「点了切换没生效」。停止态只落 override，下次启动自然读到新值；
     * 启动/停止过渡态等状态落定后再打 PATCH（重启窗口里配置已定型，直接发会被这次重启覆盖）。
     * PATCH 失败才回退一次重启（[ProxyServiceController.restartWhenReady]），保证切换最终生效；
     * 别退回无条件的 `restart()`——那会把用户没打算启动的代理拉起来。
     */
    fun switchMode(mode: String) {
        val current = overrideStore.load()
        overrideStore.save(current.copy(mode = mode))
        _uiState.value = _uiState.value.copy(mode = mode)
        modeApplyJob?.cancel()
        modeApplyJob = when (serviceController.status.value.state) {
            ProxyState.Running -> viewModelScope.launch { applyModeToLiveCore(mode) }

            ProxyState.Starting, ProxyState.Stopping -> viewModelScope.launch {
                val settled = serviceController.status.first {
                    it.state != ProxyState.Starting && it.state != ProxyState.Stopping
                }
                if (settled.state == ProxyState.Running) applyModeToLiveCore(mode)
            }

            ProxyState.Stopped, ProxyState.Error -> null
        }
    }

    /**
     * 把运行模式打到正在跑的内核上（PATCH /configs）。
     * 内核客户端还没建好（Running 刚发布、连接管理器还在建客户端）或 PATCH 失败时回退一次重启
     * ——切换模式是用户可见的操作，宁可慢一次也不能静默丢失。
     */
    private suspend fun applyModeToLiveCore(mode: String) {
        val api = repository ?: run {
            serviceController.restartWhenReady(getActiveSubscriptionId())
            return
        }
        if (api.setMode(mode).isSuccess) {
            // 后台断存量连接：不阻塞点击响应；失败（内核刚停 / 超时）不影响本次切换
            viewModelScope.launch { api.closeAllConnections() }
        } else {
            serviceController.restartWhenReady(getActiveSubscriptionId())
        }
    }

    /** Current app-level TUN device override, used by ROOT settings to show the effective source. */
    val rootTunDeviceOverride: String?
        get() = overrideStore.load().tun?.device?.trim()?.takeIf { it.isNotEmpty() }

    /** App-managed tun fields are the writable fallback for encrypted/transformed profiles. */
    val rootTunOverride: TunOverride?
        get() = overrideStore.load().tun

    fun switchTunStack(stack: String) {
        // TPROXY / EBPF 模式 tun.enable=false，切 stack 无意义（且不应触发 restart）
        if (_uiState.value.tunMode == TunMode.RootTproxy ||
            _uiState.value.tunMode == TunMode.RootEbpf ||
            stack.isBlank()
        ) {
            return
        }
        val subscriptionId = getActiveSubscriptionId()
        viewModelScope.launch {
            val outcome = persistActiveTunValues(subscriptionId, mapOf("stack" to stack))
            if (outcome.failed) return@launch
            if (outcome.encrypted) showToast(context.getString(R.string.tun_profile_encrypted_override))
            _uiState.update { it.copy(tunStack = stack) }
            serviceController.restart(subscriptionId)
        }
    }

    /** Persist the Root TUN MTU/GSO switch into active-profile tun fields (source or app fallback). */
    fun setRootTunJumboMtu(enabled: Boolean) {
        val values = linkedMapOf<String, Any?>(
            "mtu" to if (enabled) 9000 else 1500,
            "gso" to enabled,
            "gso-max-size" to if (enabled) 65535 else null,
        )
        val subscriptionId = getActiveSubscriptionId()
        viewModelScope.launch {
            val outcome = persistActiveTunValues(subscriptionId, values)
            if (outcome.encrypted) showToast(context.getString(R.string.tun_profile_encrypted_override))
        }
    }

    /**
     * Update comment-preserving profile YAML when possible. Age ciphertext is immutable, while
     * selected transforms may rewrite the edited fields; in either case the app override is kept
     * as the explicit user edit so the next runtime uses the requested value.
     */
    private suspend fun persistActiveTunValues(
        subscriptionId: String?,
        values: Map<String, Any?>,
    ): TunConfigUpdateOutcome = profileTunWriteMutex.withLock {
        val profile = activeSubscription.value?.takeIf { it.id == subscriptionId }
        val hasTransform = profile?.overrideIds?.isNotEmpty() == true
        val result = try {
            withContext(Dispatchers.IO) {
                ActiveProfileRuntimeConfig.updateTunValues(context, subscriptionId, values)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showToast(context.getString(R.string.error_save_failed, e.message ?: e.javaClass.simpleName))
            return@withLock TunConfigUpdateOutcome(failed = true)
        }
        val keepAppOverride = result.encrypted || hasTransform
        val current = overrideStore.load()
        var tun = current.tun ?: TunOverride()
        if ("stack" in values) tun = tun.copy(stack = if (keepAppOverride) values["stack"] as? String else null)
        if ("mtu" in values) tun = tun.copy(mtu = if (keepAppOverride) values["mtu"] as? Int else null)
        if ("gso" in values) tun = tun.copy(gso = if (keepAppOverride) values["gso"] as? Boolean else null)
        if ("gso-max-size" in values) {
            tun = tun.copy(gsoMaxSize = if (keepAppOverride) values["gso-max-size"] as? Int else null)
        }
        overrideStore.save(current.copy(tun = tun.takeIf { it != TunOverride() }))

        TunConfigUpdateOutcome(encrypted = result.encrypted)
    }

    fun reloadConfig() {
        serviceController.restart(getActiveSubscriptionId())
    }

    /**
     * 三个探针一律经 [RuleLatencyTester] 按规则实测；mixed-port 解析不到时退回 GLOBAL 组拨测，
     * 并置 `latencyViaRules = false` 让 UI 标注，不静默给出误导性数字。
     */
    fun testLatency() {
        if (latencyJob?.isActive == true) return
        if (repository == null) return
        _uiState.value = _uiState.value.copy(isTestingLatency = true)

        latencyJob = viewModelScope.launch {
            try {
                // 每个探针自己回报是否走了规则，不共写标志位
                val viaRules = LatencyProbe.entries.map { probe ->
                    async {
                        val delay = latencyTester.measure(probe.url, LATENCY_TIMEOUT_MILLIS)
                        if (delay == RuleLatencyTester.Unavailable) {
                            fallbackProbe(probe)
                            false
                        } else {
                            applyLatency(probe, delay)
                            true
                        }
                    }
                }.awaitAll()
                _uiState.value = _uiState.value.copy(latencyViaRules = viaRules.all { it })
            } finally {
                _uiState.value = _uiState.value.copy(isTestingLatency = false)
            }
        }
    }

    /** mixed-port 不可用时的降级：GLOBAL 组拨测，至少能反映节点本身通不通 */
    private suspend fun fallbackProbe(probe: LatencyProbe) {
        val result = repository?.getProxyDelay("GLOBAL", probe.url, LATENCY_TIMEOUT_MILLIS.toInt())
        applyLatency(probe, result?.getOrNull()?.delay ?: -1)
    }

    private fun applyLatency(probe: LatencyProbe, delay: Int) {
        _uiState.update {
            when (probe) {
                LatencyProbe.Baidu -> it.copy(latencyBaidu = delay)
                LatencyProbe.Cloudflare -> it.copy(latencyCloudflare = delay)
                LatencyProbe.Google -> it.copy(latencyGoogle = delay)
            }
        }
    }

    /**
     * 仅 cancel 自身订阅的流；不 close repository（owner 是 connectionManager）。
     * 当 repository StateFlow 切回 null 时由收集回调调用。
     */
    private fun disconnectStreams() {
        initialLoadJob?.cancel()
        clearProviderTraffic()
        repositorySubscriptionId = null
        trafficJob?.cancel()
        memoryJob?.cancel()
        systemInfoJob?.cancel()
        runtimeConfigJob?.cancel()
        uptimeJob?.cancel()
        // 一轮延迟测试最长 5s，不取消的话旧结果会在切换后回写进新订阅的 UI
        latencyJob?.cancel()
        _uiState.update { it.copy(isTestingLatency = false) }
        // 旧 client 已被 close，差分基准随之失效
        stopConnectionRateTracking()
        // mihomo 断开时清掉 live provider，订阅页立即回退到 DB 数据
        onLiveProviderInfo(null, null)
        mihomoPid = -1
    }

    companion object {
        /** 折线图窗口点数；mihomo `/traffic` 为 1Hz 推送，即约 1 分钟历史 */
        const val TRAFFIC_HISTORY_CAPACITY = 60

        private const val LATENCY_TIMEOUT_MILLIS = 5000L

        /** 速度卡详情展示的连接条数 */
        private const val TOP_CONNECTION_COUNT = 5
    }
}
