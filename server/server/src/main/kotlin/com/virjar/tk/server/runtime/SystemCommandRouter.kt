package com.virjar.tk.server.runtime

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.server.domain.message.SystemCommandHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch

/**
 * 服务号指令路由（内测反馈 T058，设计稿 §14）：sys_service 收到的文本消息解析为指令，
 * 回复以 sys_service 身份走正常发送链路。
 *
 * Application 在消息存储关闭前取消并排空本运行时。回复失败不改变已提交原消息的 ACK，
 * 目前也不承诺失败后的持久重试；回复自身仍沿普通发送链按稳定 clientMsgId 幂等。
 */
internal class SystemCommandRouter(
    private val sendServiceReply: suspend (chatId: String, clientMsgId: String, markdown: String) -> Long,
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

    override fun onServiceMessage(senderUid: String, chatId: String, clientMsgId: String, text: String) {
        scope.launch {
            try {
                sendServiceReply(chatId, replyId(clientMsgId), replyFor(text.trim()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn("服务号指令处理失败", failure)
            }
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

    private fun replyId(clientMsgId: String): String {
        val legacy = "svc-$clientMsgId"
        if (legacy.length <= MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH) return legacy
        // 保留已经分发的短 ID；长 ID 编码完整身份，不能截断最后四个合法字符。
        val digest = MessageDigest.getInstance("SHA-256").digest(clientMsgId.toByteArray(Charsets.UTF_8))
        // 与旧映射的 svc- 前缀分开，避免合法短 ID 恰好等于长 ID 的摘要名称。
        return "svc.sha256:" + HexFormat.of().formatHex(digest)
    }

    /** 指令解析：/help 显示帮助；其余 / 指令回退帮助；普通文本同样回退帮助。 */
    internal fun replyFor(text: String): String = when {
        text.equals("/help", ignoreCase = true) -> HELP_TEXT
        text.startsWith("/") -> "未识别指令：$text\n\n$HELP_TEXT"
        else -> HELP_TEXT
    }

    internal companion object {
        val HELP_TEXT = """
            服务号可用指令：
            - /help 显示本帮助

            直接发送其他内容会收到本提示。更多指令即将上线。
        """.trimIndent()
    }
}
