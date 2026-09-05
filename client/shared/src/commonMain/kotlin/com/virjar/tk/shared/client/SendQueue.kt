package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 显式会话退役是否保留同一账号下次会话的持久工作。 */
enum class SendQueueCloseDisposition { PRESERVE, CANCEL }

/** null 表示身份和序号均有效的成功 ACK；其余结果直接使用持久发送失败码。 */
internal fun outgoingAckFailureCode(
    ack: MessageAckPayload,
    expectedChatId: String,
    expectedClientMsgId: String,
): OutgoingFailureCode? = when {
    ack.chatId != expectedChatId || ack.clientMsgId != expectedClientMsgId ->
        OutgoingFailureCode.ACK_IDENTITY_MISMATCH
    ack.code == 0 && ack.serverSeq > 0L -> null
    ack.code == 0 -> OutgoingFailureCode.INVALID_ACK
    ack.code == -1 -> OutgoingFailureCode.ACK_TIMEOUT
    ack.code < 0 -> OutgoingFailureCode.TRANSPORT_UNAVAILABLE
    ack.code == 429 -> OutgoingFailureCode.RATE_LIMITED
    ack.code in 500..599 -> OutgoingFailureCode.SERVER_UNAVAILABLE
    ack.code == 401 -> OutgoingFailureCode.AUTHENTICATION_REQUIRED
    else -> OutgoingFailureCode.REMOTE_REJECTED
}

/** 自动重试保留原 clientMsgId，与用户能否主动换新身份重发是两件事。 */
internal val OutgoingFailureCode.retriesAutomatically: Boolean
    get() = when (this) {
        OutgoingFailureCode.ACK_TIMEOUT,
        OutgoingFailureCode.TRANSPORT_UNAVAILABLE,
        OutgoingFailureCode.RATE_LIMITED,
        OutgoingFailureCode.SERVER_UNAVAILABLE,
        OutgoingFailureCode.PROCESS_INTERRUPTED,
        OutgoingFailureCode.UNEXPECTED_FAILURE -> true
        OutgoingFailureCode.AUTHENTICATION_REQUIRED,
        OutgoingFailureCode.REMOTE_REJECTED,
        OutgoingFailureCode.CLIENT_VALIDATION,
        OutgoingFailureCode.ACK_IDENTITY_MISMATCH,
        OutgoingFailureCode.INVALID_ACK,
        OutgoingFailureCode.SESSION_RETIRED -> false
    }

/**
 * 账号拥有的持久 FIFO 发送者。
 *
 * 准入先把乐观消息与不可变规范线格式载荷提交到 SQLite。worker 只认领最旧的活跃本地序号。成功
 * ACK 投影与持久 SUCCESS 回执共享一个事务；不明确的结果在同一 clientMsgId 下保持可重试，让服务器
 * 的幂等边界来解决丢失的响应。
 */
class SendQueue(
    private val ownerUid: String,
    private val localCache: LocalCache,
    private val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>,
    private val sender: MessageSender,
    scope: CoroutineScope,
    private val onQueued: (Message) -> Unit = {},
    private val onSent: (Message, MessageAckPayload) -> Unit = { _, _ -> },
    private val onFailed: (Message, OutgoingFailureCode) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ackTimeoutMs: Long = 30_000L,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]),
    )
    private val callbackGate = SessionWorkGate("SendQueue")
    private val callbackLease = callbackGate.lease()
    private val worker: Job
    private val _queueSnapshots = MutableStateFlow(EMPTY_OUTGOING_QUEUE_SNAPSHOT)
    /** 转换驱动的元数据快照，供诊断/遥测使用；载荷绝不 decode。 */
    val queueSnapshots: StateFlow<OutgoingQueueSnapshot> = _queueSnapshots.asStateFlow()

    init {
        require(ownerUid.isNotBlank()) { "SendQueue owner uid must not be blank" }
        require(ackTimeoutMs > 0L) { "ackTimeoutMs must be positive" }
        callbackGate.use(callbackLease) {
            localCache.recoverOutgoingState(clock())
            refreshSnapshot()
        }
        connectionState
            .onEach {
                if (it == ConnectionState.AUTHENTICATED) {
                    callbackGate.runIfActive(callbackLease) { wake.trySend(Unit) }
                }
            }
            .launchIn(this.scope)
        worker = this.scope.launch {
            try {
                workerLoop()
            } finally {
                // 唯一消费者退出后，连接观察者也不再有工作。新增发送准入直接检查 worker，
                // 原故障照常传播；保留回执查询与显式 close(CANCEL)，让之后的登出仍能取消
                // 未完成工作，而不是提前以 PRESERVE 消费整个队列的关闭语义。
                this@SendQueue.scope.cancel()
            }
        }
        wake.trySend(Unit)
    }

    /** 返回之前同步提交；任何内存 lambda 都不是持久事实的一部分。 */
    fun enqueue(message: Message, requestFingerprint: ByteArray? = null): OutgoingMessage =
        callbackGate.use(callbackLease) {
            requireWorkerActive()
            require(message.senderUid == ownerUid) {
                "Outgoing message owner ${message.senderUid} does not match fixed session owner $ownerUid"
            }
            val canonical = canonicalizeOutboundMessage(message)
            val receipt = localCache.enqueueOutgoingMessage(canonical, clock(), requestFingerprint)
            refreshSnapshot()
            wake.trySend(Unit)
            receipt
        }

    /** 读取回执而不触及 worker 状态；期望的指纹在失配时按失败关闭处理。 */
    fun receipt(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage? = callbackGate.use(callbackLease) {
        localCache.getOutgoingMessage(chatId, clientMsgId, requestFingerprint)
    }

    /** 同步当前聚合；当 age 必须在精确调用时刻评估时有用。 */
    fun snapshot(): OutgoingQueueSnapshot = callbackGate.use(callbackLease) {
        localCache.outgoingQueueSnapshot(clock()).also { _queueSnapshots.value = it }
    }

    fun discardTerminalFailure(chatId: String, clientMsgId: String): Boolean =
        callbackGate.use(callbackLease) {
            localCache.discardTerminalFailure(ownerUid, chatId, clientMsgId).also { changed ->
                if (changed) refreshSnapshot()
            }
        }

    fun replaceTerminalFailure(
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage? = callbackGate.use(callbackLease) {
        requireWorkerActive()
        localCache.replaceTerminalFailure(
            ownerUid,
            chatId,
            clientMsgId,
            canonicalizeOutboundMessage(replacement),
            clock(),
            requestFingerprint,
        )?.also {
            refreshSnapshot()
            wake.trySend(Unit)
        }
    }

    /**
     * 在取消之前跨越回调/缓存边界。CANCEL 用于显式账号退役；PRESERVE 留下 IN_FLIGHT 行用于
     * 确定性重启恢复。
     */
    fun close(disposition: SendQueueCloseDisposition = SendQueueCloseDisposition.PRESERVE) {
        var boundaryFailure: SessionWorkGateReentrantCloseException? = null
        val newlyClosed = try {
            callbackGate.close()
        } catch (failure: SessionWorkGateReentrantCloseException) {
            boundaryFailure = failure
            true
        }
        if (!newlyClosed) return
        scope.cancel()
        if (disposition == SendQueueCloseDisposition.CANCEL) {
            localCache.cancelOutgoingMessages("cancelled by account retirement", clock())
            refreshSnapshot()
        }
        boundaryFailure?.let { throw it }
    }

    private suspend fun workerLoop() {
        while (true) {
            var head: OutgoingMessage? = null
            if (!callbackGate.runIfActive(callbackLease) {
                    head = localCache.peekNextOutgoingMessage()
                }
            ) return
            if (head == null) {
                wake.receive()
                continue
            }
            if (connectionState.value != ConnectionState.AUTHENTICATED) {
                if (!callbackGate.runIfActive(callbackLease) { onQueued(checkNotNull(head).message) }) return
                wake.receive()
                continue
            }
            val now = clock()
            if (checkNotNull(head).nextAttemptAt > now) {
                delay((checkNotNull(head).nextAttemptAt - now).coerceAtMost(MAX_RETRY_DELAY_MS))
                continue
            }
            var claimed: OutgoingMessage? = null
            if (!callbackGate.runIfActive(callbackLease) {
                    claimed = localCache.claimNextOutgoingMessage(clock())
                    if (claimed != null) refreshSnapshot()
                }
            ) return
            claimed ?: continue
            deliver(checkNotNull(claimed))
        }
    }

    private suspend fun deliver(outgoing: OutgoingMessage) {
        var failure: Exception? = null
        val ack = withTimeoutOrNull(ackTimeoutMs) {
            try {
                sender.sendAndWaitAck(outgoing.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (caught: Exception) {
                failure = caught
                null
            }
        }
        val now = clock()
        val ackFailureCode = ack?.let {
            outgoingAckFailureCode(it, outgoing.message.chatId, outgoing.message.clientMsgId)
        }
        if (ack != null && ackFailureCode == null) {
            callbackGate.runIfActive(callbackLease) {
                localCache.completeOutgoingMessage(outgoing.localOrdinal, ack, now)
                refreshSnapshot()
                onSent(outgoing.message, ack)
            }
            return
        }

        val reason = when {
            ackFailureCode == OutgoingFailureCode.ACK_IDENTITY_MISMATCH -> "ACK message identity mismatch"
            ack != null && ack.code != 0 -> ack.reason ?: "server rejected message (${ack.code})"
            ackFailureCode == OutgoingFailureCode.INVALID_ACK -> "successful ACK has no server sequence"
            failure != null -> failure.message ?: failure::class.simpleName ?: "send failed"
            else -> "send acknowledgement timed out"
        }
        val failureCode = ackFailureCode ?: when (failure) {
            is TransportUnavailableException -> OutgoingFailureCode.TRANSPORT_UNAVAILABLE
            is IllegalArgumentException -> OutgoingFailureCode.CLIENT_VALIDATION
            null -> OutgoingFailureCode.ACK_TIMEOUT
            else -> OutgoingFailureCode.UNEXPECTED_FAILURE
        }
        if (!failureCode.retriesAutomatically) {
            callbackGate.runIfActive(callbackLease) {
                localCache.markOutgoingMessageTerminalFailed(
                    outgoing.localOrdinal,
                    reason,
                    now,
                    terminalCode = ack?.code ?: if (failure is IllegalArgumentException) 400 else null,
                    failureCode = failureCode,
                )
                refreshSnapshot()
                onFailed(outgoing.message, failureCode)
            }
        } else {
            val nextAttemptAt = now + retryDelayMillis(outgoing.attemptCount)
            callbackGate.runIfActive(callbackLease) {
                localCache.markOutgoingMessageRetry(
                    outgoing.localOrdinal,
                    reason,
                    nextAttemptAt,
                    now,
                    failureCode,
                )
                refreshSnapshot()
                onQueued(outgoing.message)
                wake.trySend(Unit)
            }
        }
    }

    private fun retryDelayMillis(attemptCount: Long): Long {
        val shift = (attemptCount - 1L).coerceIn(0L, 6L).toInt()
        return (BASE_RETRY_DELAY_MS shl shift).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun refreshSnapshot() {
        _queueSnapshots.value = localCache.outgoingQueueSnapshot(clock())
    }

    /** 接收工作必须仍有消费它的 worker；关闭门禁另负责整个队列的显式生命周期。 */
    private fun requireWorkerActive() {
        check(worker.isActive) { "SendQueue worker is stopped" }
    }

    private companion object {
        const val BASE_RETRY_DELAY_MS = 500L
        const val MAX_RETRY_DELAY_MS = 30_000L
        val EMPTY_OUTGOING_QUEUE_SNAPSHOT = OutgoingQueueSnapshot(0L, 0L, 0L, null, 0L)
    }
}
