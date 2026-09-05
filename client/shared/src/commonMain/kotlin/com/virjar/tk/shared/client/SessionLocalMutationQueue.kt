package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.CancellationException

/** 高于 1,000 会话的产品上限，同时仍然拒绝病态的 UI 洪峰。 */
internal const val MAX_PENDING_SESSION_LOCAL_MUTATIONS = 2_048

/** 一次 UI 发起的本地变更无法进入其精确的已认证会话 owner。 */
class LocalMutationRejectedException(message: String) : IllegalStateException(message)

/**
 * 仅由 [SessionLocalMutationQueue] 使用的平台 worker。
 *
 * 实现是会话拥有的单一写者。提交从不等待 worker，而 [closeAndDrain] 是紧接 LocalCache 关闭之前
 * 使用的终态缓存边界。
 */
internal interface SessionLocalMutationExecutor {
    /** 异步调度 [task]；实现绝不能在返回之前调用它。 */
    fun execute(task: () -> Unit): Boolean
    fun closeAndDrain()
}

internal expect fun createSessionLocalMutationExecutor(): SessionLocalMutationExecutor

internal class SessionLocalMutationOperations(
    val setDraft: (String, String?) -> Long,
    val draftCommitted: (String, Long) -> Unit,
    val markRead: (String, Long) -> Long,
    val readCommitted: (String, Long) -> Unit,
    val insertMessage: (Message) -> Unit,
    val updateUploadProgress: (String, String, Float) -> Unit,
    val enqueueOutgoing: (Message) -> Unit,
    val discardTerminalFailure: (String, String) -> Boolean = { _, _ -> false },
    val replaceTerminalFailure: (String, String, Message) -> OutgoingMessage? = { _, _, _ -> null },
    val markMessageFailed: (String, String) -> Unit,
    val closePager: (MessagePager) -> Unit,
    val rollbackOptimisticEdit: (OptimisticMessageEditLease) -> Unit,
)

/**
 * 面向精确已认证会话本地单一写者的 UI 能力。
 *
 * 实现必须使准入非阻塞：调用方可能运行在 Compose/Main 上，而每个被接受的缓存/仓库操作在会话
 * 拥有的存储 worker 上执行。`false` 结果意味着提供的命令未被接受，且其失败回调（如果存在）
 * 已被通知。
 */
interface SessionLocalMutationWriter {
    fun setDraft(chatId: String, draft: String?, onFailure: (Throwable) -> Unit = {}): Boolean
    fun markRead(chatId: String, readSeq: Long, onFailure: (Throwable) -> Unit = {}): Boolean
    fun insertUploadingPlaceholder(
        message: Message,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean
    fun updateUploadProgress(chatId: String, clientMsgId: String, progress: Float): Boolean
    fun enqueueOutgoing(message: Message, onFailure: (Throwable) -> Unit): Boolean
    fun discardTerminalFailure(
        chatId: String,
        clientMsgId: String,
        onResult: (Boolean) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ): Boolean
    fun replaceTerminalFailure(
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        onResult: (OutgoingMessage?) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ): Boolean
    fun markMessageFailed(
        chatId: String,
        clientMsgId: String,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean
    fun closePager(pager: MessagePager): Boolean
    fun rollbackOptimisticEdit(lease: OptimisticMessageEditLease): Boolean
}

/**
 * UI 回调准入的 LocalCache 变更的精确会话有界 FIFO。
 *
 * Compose/Main 调用方只规范化数据并变更该内存队列。SQLite 与缓存锁只由 worker 触碰。草稿与
 * 上传进度按 key 取最新；已读水位按最大值合并。有序的消息/pager/edit 命令绝不互相超越。会话
 * 退役期间准入先关闭，然后每个被接受的命令在 LocalCache 之前排空。
 */
class SessionLocalMutationQueue internal constructor(
    private val ownerUid: String,
    private val operations: SessionLocalMutationOperations,
    private val executor: SessionLocalMutationExecutor = createSessionLocalMutationExecutor(),
) : SessionLocalMutationWriter {
    private val lock = Any()
    private val pending = ArrayDeque<LocalMutationCommand>()
    private val coalesced = linkedMapOf<LocalMutationKey, LocalMutationCommand>()
    private var phase = LocalMutationQueuePhase.OPEN
    private var workerScheduled = false
    private var terminalFailure: Throwable? = null
    private val cleanupFailures = BoundedLocalMutationCleanupFailures()

    init {
        require(ownerUid.isNotBlank()) { "Local mutation owner uid must not be blank" }
    }

    override fun setDraft(
        chatId: String,
        draft: String?,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        require(chatId.isNotBlank()) { "Draft chatId must not be blank" }
        return enqueueCoalesced(
            key = LocalMutationKey(LocalMutationKind.DRAFT, chatId),
            command = LocalMutationCommand.Draft(chatId, draft, onFailure),
            merge = { current, incoming ->
                check(current is LocalMutationCommand.Draft && incoming is LocalMutationCommand.Draft)
                current.copy(draft = incoming.draft, onFailure = incoming.onFailure)
            },
        )
    }

    override fun markRead(
        chatId: String,
        readSeq: Long,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        require(chatId.isNotBlank()) { "Read chatId must not be blank" }
        if (readSeq <= 0L) return false
        return enqueueCoalesced(
            key = LocalMutationKey(LocalMutationKind.READ, chatId),
            command = LocalMutationCommand.Read(chatId, readSeq, onFailure),
            merge = { current, incoming ->
                check(current is LocalMutationCommand.Read && incoming is LocalMutationCommand.Read)
                current.copy(
                    readSeq = maxOf(current.readSeq, incoming.readSeq),
                    onFailure = incoming.onFailure,
                )
            },
        )
    }

    override fun insertUploadingPlaceholder(
        message: Message,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        requireOwnedMessage(message)
        return enqueueOrdered(LocalMutationCommand.InsertMessage(message, onFailure))
    }

    override fun updateUploadProgress(
        chatId: String,
        clientMsgId: String,
        progress: Float,
    ): Boolean {
        require(chatId.isNotBlank()) { "Upload progress chatId must not be blank" }
        require(clientMsgId.isNotBlank()) { "Upload progress clientMsgId must not be blank" }
        val boundedProgress = progress.coerceIn(0f, 1f)
        return enqueueCoalesced(
            key = LocalMutationKey(LocalMutationKind.UPLOAD_PROGRESS, "$chatId\u0000$clientMsgId"),
            command = LocalMutationCommand.UploadProgress(chatId, clientMsgId, boundedProgress),
            merge = { current, incoming ->
                check(
                    current is LocalMutationCommand.UploadProgress &&
                        incoming is LocalMutationCommand.UploadProgress,
                )
                current.copy(progress = incoming.progress)
            },
        )
    }

    override fun enqueueOutgoing(
        message: Message,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        requireOwnedMessage(message)
        return enqueueOrdered(LocalMutationCommand.Send(message, onFailure))
    }

    override fun discardTerminalFailure(
        chatId: String,
        clientMsgId: String,
        onResult: (Boolean) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        requireRecoveryIdentity(chatId, clientMsgId)
        return enqueueOrdered(
            LocalMutationCommand.DiscardTerminalFailure(chatId, clientMsgId, onResult, onFailure),
        )
    }

    override fun replaceTerminalFailure(
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        onResult: (OutgoingMessage?) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        requireRecoveryIdentity(chatId, clientMsgId)
        requireOwnedMessage(replacement)
        require(replacement.chatId == chatId) { "replacement must stay in the failed message chat" }
        require(replacement.clientMsgId != clientMsgId) { "replacement must use a fresh clientMsgId" }
        return enqueueOrdered(
            LocalMutationCommand.ReplaceTerminalFailure(
                chatId,
                clientMsgId,
                replacement,
                onResult,
                onFailure,
            ),
        )
    }

    override fun markMessageFailed(
        chatId: String,
        clientMsgId: String,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        require(chatId.isNotBlank()) { "Failed-message chatId must not be blank" }
        require(clientMsgId.isNotBlank()) { "Failed-message clientMsgId must not be blank" }
        return enqueueOrdered(LocalMutationCommand.MarkFailed(chatId, clientMsgId, onFailure))
    }

    /** Pager 释放排在可能仍发布进其窗口的每次 UI 变更之后。 */
    override fun closePager(pager: MessagePager): Boolean =
        enqueueOrdered(LocalMutationCommand.ClosePager(pager))

    /** 退役回滚保持缓存拥有，并在 driver 关闭之前排空。 */
    override fun rollbackOptimisticEdit(lease: OptimisticMessageEditLease): Boolean =
        enqueueOrdered(LocalMutationCommand.RollbackEdit(lease))

    /** 与 ClientSession quiesce 一起安装的不可逆准入隔断。 */
    internal fun retireAdmission() = synchronized(lock) {
        if (phase == LocalMutationQueuePhase.OPEN) phase = LocalMutationQueuePhase.RETIRING
    }

    /**
     * 恰好排空一次已接受的工作。它刻意在 SendQueue 与 LocalCache 退役之前被调用，因此最终草稿
     * 或占位符绝不能针对已关闭的 driver 恢复。
     */
    internal fun closeAndDrain() {
        retireAdmission()
        var executorFailure: Throwable? = null
        try {
            executor.closeAndDrain()
        } catch (failure: Throwable) {
            executorFailure = failure
        }
        val failure = synchronized(lock) {
            phase = LocalMutationQueuePhase.CLOSED
            collapseMutationFailures(
                buildList {
                    terminalFailure?.let(::add)
                    executorFailure?.let(::add)
                    addAll(cleanupFailures.snapshot())
                },
            )
        }
        failure?.let { throw it }
    }

    internal fun pendingCountForTest(): Int = synchronized(lock) { pending.size }

    private fun requireOwnedMessage(message: Message) {
        require(message.senderUid == ownerUid) {
            "Local message owner ${message.senderUid} does not match fixed session owner"
        }
        require(message.chatId.isNotBlank()) { "Local message chatId must not be blank" }
        require(message.clientMsgId.isNotBlank()) { "Local message clientMsgId must not be blank" }
    }

    private fun requireRecoveryIdentity(chatId: String, clientMsgId: String) {
        require(chatId.isNotBlank()) { "Failed-message chatId must not be blank" }
        require(clientMsgId.isNotBlank()) { "Failed-message clientMsgId must not be blank" }
    }

    private fun enqueueOrdered(command: LocalMutationCommand): Boolean {
        val decision = synchronized(lock) { enqueueLocked(command) }
        decision.failure?.let { notifyRejected(command, it) }
        return decision.accepted
    }

    private fun enqueueCoalesced(
        key: LocalMutationKey,
        command: LocalMutationCommand,
        merge: (LocalMutationCommand, LocalMutationCommand) -> LocalMutationCommand,
    ): Boolean {
        val decision = synchronized(lock) {
            admissionFailureLocked()?.let { return@synchronized AdmissionDecision.rejected(it) }
            val existing = coalesced[key]
            if (existing != null) {
                val replacement = merge(existing, command)
                val index = pending.indexOfFirst { it === existing }
                check(index >= 0) { "Coalesced local mutation lost its FIFO entry" }
                pending[index] = replacement
                coalesced[key] = replacement
                return@synchronized AdmissionDecision.ACCEPTED
            }
            capacityFailureLocked()?.let { return@synchronized AdmissionDecision.rejected(it) }
            pending.addLast(command)
            coalesced[key] = command
            scheduleWorkerLocked(command)
        }
        decision.failure?.let { notifyRejected(command, it) }
        return decision.accepted
    }

    private fun enqueueLocked(command: LocalMutationCommand): AdmissionDecision {
        admissionFailureLocked()?.let { return AdmissionDecision.rejected(it) }
        capacityFailureLocked()?.let { return AdmissionDecision.rejected(it) }
        pending.addLast(command)
        return scheduleWorkerLocked(command)
    }

    private fun admissionFailureLocked(): Throwable? = when (phase) {
        LocalMutationQueuePhase.OPEN -> null
        LocalMutationQueuePhase.FAILED -> terminalFailure
            ?: LocalMutationRejectedException("Local mutation worker has failed")
        LocalMutationQueuePhase.RETIRING,
        LocalMutationQueuePhase.CLOSED ->
            LocalMutationRejectedException("Local mutation session is retired")
    }

    private fun capacityFailureLocked(): Throwable? =
        if (pending.size < MAX_PENDING_SESSION_LOCAL_MUTATIONS) null else {
            LocalMutationRejectedException("Local mutation queue reached its hard capacity")
        }

    /**
     * [SessionLocalMutationExecutor.execute] 是调度原语，绝不能内联调用任务。把调度保持在 [lock]
     * 之下使其与终态关闭线性化：要么 runnable 在退役之前归 executor 所有，要么命令被移除并拒绝。
     */
    private fun scheduleWorkerLocked(command: LocalMutationCommand): AdmissionDecision {
        if (workerScheduled) return AdmissionDecision.ACCEPTED
        workerScheduled = true
        val scheduled = try {
            executor.execute(::drainLoop)
        } catch (failure: Throwable) {
            workerScheduled = false
            pending.remove(command)
            coalesced.entries.removeAll { it.value === command }
            return AdmissionDecision.rejected(failure)
        }
        if (scheduled) return AdmissionDecision.ACCEPTED

        workerScheduled = false
        pending.remove(command)
        coalesced.entries.removeAll { it.value === command }
        return AdmissionDecision.rejected(
            LocalMutationRejectedException("Local mutation worker is unavailable"),
        )
    }

    private fun drainLoop() {
        while (true) {
            val command = synchronized(lock) {
                if (terminalFailure != null) {
                    workerScheduled = false
                    return
                }
                pending.removeFirstOrNull()?.also { selected ->
                    coalesced.entries.removeAll { it.value === selected }
                } ?: run {
                    workerScheduled = false
                    return
                }
            }
            try {
                execute(command)
            } catch (failure: Throwable) {
                if (failure is CancellationException || failure !is Exception) {
                    failWorker(command, failure)
                    return
                }
                commandFailure(command, failure)
            }
        }
    }

    /**
     * 致命存储 worker 失败是不可逆准入隔断。当前与每个排队命令恰好失败一次；pager/edit 清理
     * 命令刻意落到 LocalCache 的终态关闭，即使该队列之后抛出，ClientSession 也仍会执行它。
     */
    private fun failWorker(current: LocalMutationCommand, failure: Throwable) {
        val failedCommands = synchronized(lock) {
            terminalFailure = terminalFailure ?: failure
            if (phase != LocalMutationQueuePhase.CLOSED) phase = LocalMutationQueuePhase.FAILED
            workerScheduled = false
            buildList {
                add(current)
                addAll(pending)
            }.also {
                pending.clear()
                coalesced.clear()
            }
        }
        failedCommands.forEach { command -> commandFailure(command, failure) }
    }

    private fun execute(command: LocalMutationCommand) {
        when (command) {
            is LocalMutationCommand.Draft -> {
                val generation = operations.setDraft(command.chatId, command.draft)
                operations.draftCommitted(command.chatId, generation)
            }
            is LocalMutationCommand.Read -> {
                val desired = operations.markRead(command.chatId, command.readSeq)
                operations.readCommitted(command.chatId, desired)
            }
            is LocalMutationCommand.InsertMessage -> operations.insertMessage(command.message)
            is LocalMutationCommand.UploadProgress -> operations.updateUploadProgress(
                command.chatId,
                command.clientMsgId,
                command.progress,
            )
            is LocalMutationCommand.Send -> operations.enqueueOutgoing(command.message)
            is LocalMutationCommand.DiscardTerminalFailure -> notifyResultCallback(command.onResult) {
                operations.discardTerminalFailure(command.chatId, command.clientMsgId)
            }
            is LocalMutationCommand.ReplaceTerminalFailure -> notifyResultCallback(command.onResult) {
                operations.replaceTerminalFailure(
                    command.chatId,
                    command.clientMsgId,
                    command.replacement,
                )
            }
            is LocalMutationCommand.MarkFailed -> operations.markMessageFailed(
                command.chatId,
                command.clientMsgId,
            )
            is LocalMutationCommand.ClosePager -> operations.closePager(command.pager)
            is LocalMutationCommand.RollbackEdit -> operations.rollbackOptimisticEdit(command.lease)
        }
    }

    private fun commandFailure(command: LocalMutationCommand, failure: Throwable) {
        when (command) {
            is LocalMutationCommand.Draft -> notifyCallback(command.onFailure, failure)
            is LocalMutationCommand.Read -> notifyCallback(command.onFailure, failure)
            is LocalMutationCommand.InsertMessage -> notifyCallback(command.onFailure, failure)
            is LocalMutationCommand.Send -> notifyCallback(command.onFailure, failure)
            is LocalMutationCommand.DiscardTerminalFailure -> notifyCallback(command.onFailure, failure)
            is LocalMutationCommand.ReplaceTerminalFailure -> notifyCallback(command.onFailure, failure)
            is LocalMutationCommand.MarkFailed -> notifyCallback(command.onFailure, failure)
            is LocalMutationCommand.UploadProgress -> Unit
            is LocalMutationCommand.ClosePager,
            is LocalMutationCommand.RollbackEdit -> synchronized(lock) {
                cleanupFailures.record(failure)
            }
        }
    }

    /** 被拒绝的清理命令已由拥有缓存的终态关闭覆盖。 */
    private fun notifyRejected(command: LocalMutationCommand, failure: Throwable) {
        when (command) {
            is LocalMutationCommand.ClosePager,
            is LocalMutationCommand.RollbackEdit,
            is LocalMutationCommand.UploadProgress -> Unit
            else -> commandFailure(command, failure)
        }
    }

    /** 外部失败观察者绝不在 [lock] 之下运行，且不能破坏 Boolean 准入。 */
    private fun notifyCallback(callback: (Throwable) -> Unit, failure: Throwable) {
        try {
            callback(failure)
        } catch (callbackFailure: Throwable) {
            synchronized(lock) { cleanupFailures.record(callbackFailure) }
        }
    }

    private fun <T> notifyResultCallback(callback: (T) -> Unit, block: () -> T) {
        val result = block()
        try {
            callback(result)
        } catch (callbackFailure: Throwable) {
            synchronized(lock) { cleanupFailures.record(callbackFailure) }
        }
    }

}

private data class LocalMutationKey(val kind: LocalMutationKind, val identity: String)
private enum class LocalMutationKind { DRAFT, READ, UPLOAD_PROGRESS }
private enum class LocalMutationQueuePhase { OPEN, RETIRING, FAILED, CLOSED }

private data class AdmissionDecision(val accepted: Boolean, val failure: Throwable?) {
    companion object {
        val ACCEPTED = AdmissionDecision(accepted = true, failure = null)
        fun rejected(failure: Throwable) = AdmissionDecision(accepted = false, failure = failure)
    }
}

private sealed interface LocalMutationCommand {
    data class Draft(
        val chatId: String,
        val draft: String?,
        val onFailure: (Throwable) -> Unit,
    ) : LocalMutationCommand

    data class Read(
        val chatId: String,
        val readSeq: Long,
        val onFailure: (Throwable) -> Unit,
    ) : LocalMutationCommand

    data class InsertMessage(
        val message: Message,
        val onFailure: (Throwable) -> Unit,
    ) : LocalMutationCommand

    data class UploadProgress(
        val chatId: String,
        val clientMsgId: String,
        val progress: Float,
    ) : LocalMutationCommand

    data class Send(
        val message: Message,
        val onFailure: (Throwable) -> Unit,
    ) : LocalMutationCommand

    data class DiscardTerminalFailure(
        val chatId: String,
        val clientMsgId: String,
        val onResult: (Boolean) -> Unit,
        val onFailure: (Throwable) -> Unit,
    ) : LocalMutationCommand

    data class ReplaceTerminalFailure(
        val chatId: String,
        val clientMsgId: String,
        val replacement: Message,
        val onResult: (OutgoingMessage?) -> Unit,
        val onFailure: (Throwable) -> Unit,
    ) : LocalMutationCommand

    data class MarkFailed(
        val chatId: String,
        val clientMsgId: String,
        val onFailure: (Throwable) -> Unit,
    ) : LocalMutationCommand

    data class ClosePager(val pager: MessagePager) : LocalMutationCommand
    data class RollbackEdit(val lease: OptimisticMessageEditLease) : LocalMutationCommand
}

private fun collapseMutationFailures(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val primary = failures.firstOrNull { it.isTerminalMutationFailure() } ?: failures.first()
    failures.forEach { failure ->
        if (
            failure !== primary &&
            primary.suppressedExceptions.none { suppressed -> suppressed === failure }
        ) {
            primary.addSuppressed(failure)
        }
    }
    return primary
}
