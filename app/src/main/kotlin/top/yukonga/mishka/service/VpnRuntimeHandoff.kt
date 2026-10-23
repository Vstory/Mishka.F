package top.yukonga.mishka.service

import kotlinx.coroutines.CompletableDeferred

/** 前一实例释放资源并发布终态后，下一实例才能开始创建 VPN 资源。 */
internal object VpnRuntimeHandoff {
    private var tail = CompletableDeferred(Unit)

    @Synchronized
    fun claim(): Lease {
        val finished = CompletableDeferred<Unit>()
        return Lease(tail, finished).also { tail = finished }
    }

    internal class Lease(
        private val predecessor: CompletableDeferred<Unit>,
        private val finished: CompletableDeferred<Unit>,
    ) {
        suspend fun awaitPredecessor() = predecessor.await()

        fun finish() {
            finished.complete(Unit)
        }
    }
}
