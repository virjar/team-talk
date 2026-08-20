package com.virjar.tk.client

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 发送队列与断线重试（roadmap P2）。
 *
 * 模型：串行 worker + Channel 唤醒。入队总是成功（在线时等价直发），状态机经回调推进
 * （SENDING→QUEUED→SENT/FAILED 由持有方写本地缓存）。断线时 worker 挂起在 wake 通道上，
 * AUTHENTICATED 唤醒继续——保序 FIFO，毒丸消息只失败自己（30s ack 超时）。
 *
 * 不自动重试：认证类失败重试无意义；网络类失败由断线等待机制天然覆盖（重连即续发）。
 * 队列为会话级内存态，不落库（重启后残留 FAILED 态由用户手动重发，避免与消息表双事实源）。
 */
class SendQueue(
    private val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>,
    private val sender: MessageSender,
    scope: CoroutineScope,
    private val onQueued: (Message) -> Unit = {},
    private val onSent: (Message, MessageAckPayload) -> Unit = { _, _ -> },
    private val onFailed: (Message, String) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<Message>()
    private val wake = Channel<Unit>(Channel.CONFLATED) // 唤醒信号可合并
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    /** 停止队列（会话关闭）。 */
    fun close() {
        // scope.cancel() 会取消 worker 中挂起的 receive；随后再 wake.close() 会把
        // receive 竞态恢复成 ClosedReceiveChannelException，泄漏为下一项 runTest 的
        // UncaughtExceptionsBeforeTest。Channel 是队列私有对象，随 owner 一起回收即可。
        scope.cancel()
    }

    init {
        connectionState
            .onEach { if (it == ConnectionState.AUTHENTICATED) wake.trySend(Unit) }
            .launchIn(scope)
        scope.launch { workerLoop() }
    }

    /** 入队（总是接受；结果经 onSent/onFailed 回调）。 */
    fun enqueue(message: Message) {
        scope.launch {
            mutex.withLock { queue.addLast(message) }
            wake.trySend(Unit)
        }
    }

    private suspend fun workerLoop() {
        while (true) {
            val msg = mutex.withLock { queue.firstOrNull() }
            if (msg == null) {
                wake.receive() // 挂起直到新消息或重连
                continue
            }
            if (connectionState.value != ConnectionState.AUTHENTICATED) {
                onQueued(msg) // 渲染层显示「排队中」
                wake.receive() // 挂起直到重连（或新消息重复唤醒，无副作用）
                continue
            }
            var sendFailure: Throwable? = null
            var retryAfterDisconnect = false
            val ack = withTimeoutOrNull(30_000) {
                try {
                    sender.sendAndWaitAck(msg)
                } catch (_: AckTransportDisconnectedException) {
                    // Keep the head item. The outer loop either observes the already-published
                    // non-ready state and waits, or immediately retries after a fast reconnect.
                    onQueued(msg)
                    retryAfterDisconnect = true
                    return@withTimeoutOrNull null
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    sendFailure = failure
                    null
                }
            }
            if (retryAfterDisconnect) continue
            if (ack != null && ack.code == 0) {
                onSent(msg, ack)
            } else {
                val reason = ack?.takeIf { it.code != 0 }?.reason
                    ?: sendFailure?.message
                    ?: "发送超时"
                onFailed(msg, reason)
            }
            mutex.withLock { queue.removeFirstOrNull() }
        }
    }
}
