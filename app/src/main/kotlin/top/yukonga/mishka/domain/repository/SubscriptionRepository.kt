package top.yukonga.mishka.domain.repository

import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.model.SubscriptionInfo

/**
 * 订阅仓库门面（消费方视角）。实现见 data 层 `SubscriptionRepositoryImpl`。
 *
 * 仅暴露实体无关的消费方 API；ProfileProcessor 使用的实体级方法（withProfileLock /
 * queryPending / commitPending 等）留在实现类内，属 data 层内部编排。
 */
interface SubscriptionRepository {

    /** 合并 pending > live provider > imported 三层后的订阅视图。 */
    val subscriptions: StateFlow<ImmutableList<Subscription>>

    /** 当前活跃订阅（含 mihomo runtime 聚合流量）。 */
    val activeSubscription: StateFlow<Subscription?>

    /**
     * 直接读 DB 的订阅快照（imported + pending 合并，字段优先级与 [subscriptions] 一致）。
     *
     * 给「内置免流配置对账」用的：subscriptions 的首个值是构造时的空列表，DB 回填前按它判断
     * 「订阅被删了没」会在每次冷启动误判一次；这里从 DB 现读，不依赖 Flow 的回填时机。
     */
    suspend fun snapshot(): List<Subscription>

    /** 推送 mihomo runtime 聚合后的 live provider 流量；传 null 清空。 */
    fun setLiveProviderInfo(subscriptionId: String?, info: SubscriptionInfo?)

    /** 切换活跃订阅（同步写，调用方随后的 restart 立即可读）。 */
    fun setActive(id: String)

    fun getActive(): Subscription?

    /** 立即返回已选订阅 ID，不等待订阅列表 Flow 回填。 */
    fun getActiveId(): String?

    /** 保存订阅选择的覆写 ID 与执行顺序。 */
    suspend fun setOverrideSelection(subscriptionId: String, overrideIds: List<String>, sortPreference: List<String>)

    suspend fun create(
        type: ProfileType,
        name: String,
        source: String,
        interval: Long = 0,
        userAgent: String = "",
        ageSecretKey: String = "",
    ): Subscription

    suspend fun patch(
        uuid: String,
        name: String,
        source: String,
        interval: Long,
        userAgent: String,
        ageSecretKey: String,
    )

    /** 放弃编辑，丢弃 Pending。 */
    suspend fun release(uuid: String)

    /** 删除订阅（清 Imported/Pending/Selection）。 */
    suspend fun delete(uuid: String)

    /**
     * 校验编辑中的 pending 字段（invalid 抛 ImportError），返回是否为「需重新拉取的 URL 订阅」。
     * true → 调用方走 ProfileProcessor.apply 重新 fetch；false → 调用 [commitPendingProfile] 直接提交。
     */
    suspend fun validatePendingForCommit(uuid: String): Boolean

    /** 直接提交 pending → imported（File 类型编辑，无需重新拉取）。 */
    suspend fun commitPendingProfile(uuid: String)
}
