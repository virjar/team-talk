package com.virjar.tk.server.runtime

import com.virjar.tk.server.domain.message.ServiceAccountMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务号广播运行时：持有全员广播的执行协程。与 [SystemCommandRouter] 相同的关闭纪律——
 * Application 在存储关闭前取消并排空；执行中断时已写入的待回复记录由指令运行时在下次
 * 启动恢复，未扫描的尾部广播由 [ServiceAccountMessages.resumeUnfinishedBroadcasts] 续跑。
 */
internal class ServiceBroadcastRuntime(
    private val runBroadcast: suspend (String) -> Unit,
    shutdownTimeoutMillis: Long = 5_000L,
) : CoroutineWorkerRuntime("ServiceBroadcastRuntime", shutdownTimeoutMillis) {
    private val logger = LoggerFactory.getLogger(ServiceBroadcastRuntime::class.java)
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun isRunning(broadcastId: String): Boolean = broadcastId in active

    /** 同一广播不并发重跑；关闭后不再接受新执行（未完成部分留待启动续跑）。 */
    fun launch(broadcastId: String) {
        if (!active.add(broadcastId)) return
        scope.launch {
            try {
                runBroadcast(broadcastId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.error("服务号广播执行失败：broadcastId={}", broadcastId, failure)
            } finally {
                active.remove(broadcastId)
            }
        }
    }
}
