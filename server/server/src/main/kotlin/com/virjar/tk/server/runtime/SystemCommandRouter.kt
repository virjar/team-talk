package com.virjar.tk.server.runtime

import com.virjar.tk.server.domain.message.PendingServiceReply
import com.virjar.tk.server.domain.message.ServiceCommandReplies
import com.virjar.tk.server.domain.message.SystemCommandHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

/**
 * 服务号指令路由（内测反馈 T058，恢复语义 CODE-01）：sys_service 收到的文本消息解析为指令，
 * 回复以 sys_service 身份走正常发送链路。
 *
 * 恢复契约：原消息提交时已在权威存储留下冻结的 [PendingServiceReply]。本运行时负责把记录
 * 推进到终态——发送成功，或被发送链以参数校验类拒绝（成员/正文预算等不可重试条件）终态放弃；
 * 两者都会结算（删除）记录。其他失败保留记录，等 [recoverPendingServiceReplies] 在启动时重放。
 * 回复 clientMsgId 与正文来自记录冻结值，发送链按 clientMsgId 幂等，因此任何时点的重放——
 * 即时派发、命令重试补派发与启动恢复——都不会产生第二条回复消息；已成功原消息的幂等 ACK
 * 与回复状态互不解释。
 *
 * Application 在消息存储关闭前取消并排空本运行时。
 */
internal class SystemCommandRouter(
    private val sendServiceReply: suspend (chatId: String, clientMsgId: String, markdown: String) -> Long,
    private val settleServiceReply: (PendingServiceReply) -> Unit,
    private val pendingServiceReplies: (limit: Int) -> List<PendingServiceReply>,
    shutdownTimeoutMillis: Long = 5_000L,
) : SystemCommandHandler, AutoCloseable {
    private val logger = LoggerFactory.getLogger(SystemCommandRouter::class.java)
    private val lifecycle = SupervisorJob()
    private val scope = CoroutineScope(lifecycle + Dispatchers.IO)
    private val finished = CountDownLatch(1)
    private val closeGate = BoundedCloseGate("SystemCommandRouter", shutdownTimeoutMillis, onTerminal = {})

    val workersTerminated: Boolean get() = finished.count == 0L

    init {
        lifecycle.invokeOnCompletion { finished.countDown() }
    }

    override fun dispatchServiceReply(pending: PendingServiceReply) {
        scope.launch { attempt(pending) }
    }

    /** 启动恢复：同步排空仍未结算的回复记录，返回本轮实际尝试推进的条数。 */
    suspend fun recoverPendingServiceReplies(pageSize: Int = 100): Int {
        require(pageSize > 0) { "Service reply recovery page size must be positive" }
        val attempted = mutableSetOf<PendingServiceReply>()
        var attemptedCount = 0
        while (true) {
            val page = pendingServiceReplies(pageSize)
            if (page.isEmpty()) return attemptedCount
            val fresh = page.filterNot(attempted::contains)
            // 本轮已尝试且仍未结算的记录都是暂态失败：留待下一次启动，不空转。
            if (fresh.isEmpty()) return attemptedCount
            for (pending in fresh) {
                attempted += pending
                attempt(pending)
                attemptedCount += 1
            }
        }
    }

    private suspend fun attempt(pending: PendingServiceReply) {
        try {
            sendServiceReply(pending.chatId, pending.replyClientMsgId, pending.markdown)
            settleServiceReply(pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (terminal: IllegalArgumentException) {
            // 成员资格、正文预算等发送链参数拒绝不会因重试而改变：终态放弃并结算记录。
            logger.warn(
                "服务号回复被发送链拒绝，终态放弃：chatId={}, clientMsgId={}",
                pending.chatId,
                pending.clientMsgId,
                terminal,
            )
            settleServiceReply(pending)
        } catch (failure: Exception) {
            logger.warn("服务号回复暂未完成，留待启动恢复：chatId={}", pending.chatId, failure)
        }
    }

    override fun close() {
        val failure = when (val attempt = closeGate.begin()) {
            is BoundedCloseGate.Attempt.Owner -> {
                lifecycle.cancel(CancellationException("SystemCommandRouter is closing"))
                val completed = attempt.deadline.awaitBlocking(finished) { closeGate.recordFailure(it) }
                if (completed) closeGate.complete(attempt) else closeGate.expire(attempt.deadline)
            }
            is BoundedCloseGate.Attempt.Follower -> closeGate.awaitFollowerBlocking(attempt)
            is BoundedCloseGate.Attempt.Terminal -> attempt.failure
        }
        failure?.let { throw it }
    }
}
