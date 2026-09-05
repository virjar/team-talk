package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingGroupFileCommand
import com.virjar.tk.shared.client.PendingGroupFileCommandKind
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.GroupFileRpcProxy
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 不含秘密的终局提示，用于在后台回放之后收敛 UI。 */
data class GroupFileCommandCompletion(
    val chatId: String,
    val entryId: String,
    val parentId: String?,
    val kind: PendingGroupFileCommandKind,
    val status: GroupFileCommandCompletionStatus = GroupFileCommandCompletionStatus.ACKNOWLEDGED,
)

enum class GroupFileCommandCompletionStatus {
    ACKNOWLEDGED,
    REJECTED,
}

/** 不可变命令已提交到本地 outbox 之后的前台结果。 */
enum class GroupFileCommandSubmission {
    ACKNOWLEDGED,
    PENDING,
}

/** 群共享文件 SDK；上传仍由 [FileRepository] 完成，发布后才成为群文件版本。 */
class GroupFileRepository internal constructor(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache?,
    private val newEntryId: () -> String = { UUID.randomUUID().toString() },
    private val newCommandId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onPendingReliableCommandCommitted: () -> Unit = {},
    private val onPendingGroupFileCommandCompleted: (GroupFileCommandCompletion) -> Unit = {},
) {
    /** 测试替身用：带本地缓存的最小构造。 */
    constructor(rpcClient: RpcInvoker, localCache: LocalCache?) : this(
        rpcClient = rpcClient,
        localCache = localCache,
        newEntryId = { UUID.randomUUID().toString() },
        newCommandId = { UUID.randomUUID().toString() },
        nowMillis = System::currentTimeMillis,
        onPendingReliableCommandCommitted = {},
        onPendingGroupFileCommandCompleted = {},
    )

    private val rpc = GroupFileRpcProxy(rpcClient)
    /** 前台与恢复一次只发送一个代次；持久意图的选择先发生。 */
    private val commandSendMutex = Mutex()

    /** 类型化引用的打开校验：按当前群成员身份读取单个条目。 */
    suspend fun getEntry(chatId: String, entryId: String): Outcome<com.virjar.tk.protocol.model.GroupFileEntry> = outcome {
        rpc.getEntry(chatId, entryId)
    }

    /**
     * 拉取目录页并原子替换本地投影（CONTENT-01）。权威页成功后缓存收敛；403/404 清空该群
     * 投影——离线时调用方读取 [cachedDirectory] 做明确的 stale 展示，不作为远端操作依据。
     */
    suspend fun list(chatId: String, parentId: String? = null): Outcome<List<GroupFileEntry>> =
        outcome {
            val page = rpc.list(chatId, parentId)
            localCache?.replaceGroupFileDirectory(chatId, parentId, page)
            page
        }

    /** 本地目录投影（stale 展示与缓存首帧）；无缓存返回 null。 */
    suspend fun cachedDirectory(chatId: String, parentId: String?): List<GroupFileEntry>? =
        localCache?.activeGroupFileEntries(chatId, parentId)?.takeIf { it.isNotEmpty() }

    /** 权威读取失败后的投影清理（403 非成员/群解散、404 不存在）。 */
    suspend fun purgeProjectionAfterFailure(chatId: String) {
        localCache?.purgeGroupFileProjection(chatId)
    }

    suspend fun createFolder(
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
    ): Outcome<GroupFileEntry> = outcome { rpc.createFolder(entryId, commandId, chatId, parentId, name) }

    /** App 边界：在 RPC 之前持久化或复用完整的不可变创建命令。 */
    suspend fun createRecoverableFolder(
        chatId: String,
        parentId: String?,
        name: String,
    ): Outcome<GroupFileCommandSubmission> = withOutbox { cache ->
        prepareAndSubmit(cache) {
            PendingGroupFileCommand.createFolder(
                commandId = newCommandId(),
                entryId = newEntryId(),
                chatId = chatId,
                parentId = parentId,
                name = name,
                createdAt = nowMillis(),
            )
        }
    }

    suspend fun createFile(
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
        attachment: Attachment,
    ): Outcome<GroupFileEntry> = outcome {
        rpc.createFile(entryId, commandId, chatId, parentId, name, attachment)
    }

    /** App 边界：已上传的附件描述符在发布之前就被冻结进 SQLite。 */
    suspend fun createRecoverableFile(
        chatId: String,
        parentId: String?,
        name: String,
        attachment: Attachment,
    ): Outcome<GroupFileCommandSubmission> = withOutbox { cache ->
        prepareAndSubmit(cache) {
            PendingGroupFileCommand.createFile(
                commandId = newCommandId(),
                entryId = newEntryId(),
                chatId = chatId,
                parentId = parentId,
                name = name,
                attachment = attachment,
                createdAt = nowMillis(),
            )
        }
    }

    suspend fun addVersion(
        commandId: String,
        chatId: String,
        entryId: String,
        attachment: Attachment,
        expectedRevision: Long,
    ): Outcome<GroupFileEntry> = outcome {
        rpc.addVersion(commandId, chatId, entryId, attachment, expectedRevision)
    }

    suspend fun addRecoverableVersion(
        chatId: String,
        entryId: String,
        attachment: Attachment,
        expectedRevision: Long,
    ): Outcome<GroupFileCommandSubmission> = withOutbox { cache ->
        prepareAndSubmit(cache) {
            PendingGroupFileCommand.addVersion(
                commandId = newCommandId(),
                chatId = chatId,
                entryId = entryId,
                attachment = attachment,
                expectedRevision = expectedRevision,
                createdAt = nowMillis(),
            )
        }
    }

    internal suspend fun retryPendingCommands(): Outcome<Unit> {
        val cache = requireNotNull(localCache) { "Recoverable group-file commands require LocalCache" }
        return retryPendingMirrors(cache.getPendingGroupFileCommands()) { pending ->
            outcome {
                commandSendMutex.withLock {
                    // 前台发送者可能已经完成或确定性地拒绝了这一精确
                    // 代次，而 worker 还在等待。绝不要重放过期的快照行。
                    if (cache.getPendingGroupFileCommands().none { it.commandId == pending.commandId }) {
                        return@withLock
                    }
                    sendPending(pending, publishCompletion = true)
                }
            }
        }
    }

    /** 恢复只需要一份持久 ACK；可见条目通过调用方的刷新来收敛。 */
    private suspend fun sendPending(
        pending: PendingGroupFileCommand,
        publishCompletion: Boolean = false,
    ) {
        val cache = requireNotNull(localCache) { "Recoverable group-file commands require LocalCache" }
        try {
            when (pending.kind) {
                PendingGroupFileCommandKind.CREATE_FOLDER -> {
                    rpc.createFolder(
                        pending.entryId,
                        pending.commandId,
                        pending.chatId,
                        pending.parentId,
                        requireNotNull(pending.name),
                    )
                    Unit
                }

                PendingGroupFileCommandKind.CREATE_FILE -> {
                    rpc.createFile(
                        pending.entryId,
                        pending.commandId,
                        pending.chatId,
                        pending.parentId,
                        requireNotNull(pending.name),
                        requireNotNull(pending.attachment),
                    )
                    Unit
                }

                PendingGroupFileCommandKind.ADD_VERSION -> {
                    rpc.addVersion(
                        pending.commandId,
                        pending.chatId,
                        pending.entryId,
                        requireNotNull(pending.attachment),
                        requireNotNull(pending.expectedRevision),
                    )
                    Unit
                }

                PendingGroupFileCommandKind.RENAME -> rpc.rename(
                    pending.commandId,
                    pending.chatId,
                    pending.entryId,
                    requireNotNull(pending.name),
                    requireNotNull(pending.expectedRevision),
                )

                PendingGroupFileCommandKind.DELETE -> rpc.delete(
                    pending.commandId,
                    pending.chatId,
                    pending.entryId,
                    requireNotNull(pending.expectedRevision),
                )
            }
        } catch (failure: Exception) {
            if (failure.isDefinitiveReliableCommandRejection()) {
                val cleared = cache.clearPendingGroupFileCommand(pending.commandId)
                if (cleared && publishCompletion) {
                    onPendingGroupFileCommandCompleted(pending.completion(GroupFileCommandCompletionStatus.REJECTED))
                }
            }
            throw failure
        }
        if (cache.clearPendingGroupFileCommand(pending.commandId) && publishCompletion) {
            onPendingGroupFileCommandCompleted(pending.completion(GroupFileCommandCompletionStatus.ACKNOWLEDGED))
        }
    }

    /**
     * 可重试的前台失败并不是一次失败的用户操作：完整命令已经
     * 持久化，会话恢复 worker 会重放这一精确代次。恢复本身
     * 仍使用 [sendPending]，因此失败继续驱动其有界退避。
     */
    private suspend fun submitPending(pending: PendingGroupFileCommand): GroupFileCommandSubmission =
        try {
            sendPending(pending)
            GroupFileCommandSubmission.ACKNOWLEDGED
        } catch (failure: Exception) {
            if (failure.isRetryableReliableCommandFailure()) {
                GroupFileCommandSubmission.PENDING
            } else {
                throw failure
            }
        }

    private suspend fun prepareAndSubmit(
        cache: LocalCache,
        createPending: () -> PendingGroupFileCommand,
    ): GroupFileCommandSubmission {
        // 在等待飞行中的 worker 之前先选定持久代次。如果恢复在
        // 本调用方等待期间 ACK 了那一行，调用方仍会重放相同的 command id 并读取
        // 它的服务端回执，而不是为同一次点击制造第二个代次。
        val pending = cache.preparePendingGroupFileCommand(createPending())
        var recoveryWoken = false
        fun wakeRecovery() {
            if (recoveryWoken) return
            recoveryWoken = true
            onPendingReliableCommandCommitted()
        }
        return try {
            commandSendMutex.withLock {
                // 只有在这个前台发送者占有单飞通道之后才唤醒。由该唤醒启动的
                // worker 因此无法超越它并发出一次不必要的重放。
                wakeRecovery()
                submitPending(pending)
            }
        } finally {
            // 等待更旧代次期间被取消时，绝不能搁置那条在
            // 本协程到达互斥锁之前就已提交的行。
            wakeRecovery()
        }
    }

    private suspend fun <T> withOutbox(block: suspend (LocalCache) -> T): Outcome<T> = outcome {
        block(requireNotNull(localCache) { "Recoverable group-file commands require LocalCache" })
    }

    suspend fun listVersions(chatId: String, entryId: String): Outcome<List<GroupFileVersion>> =
        outcome { rpc.listVersions(chatId, entryId) }

    suspend fun rename(
        commandId: String,
        chatId: String,
        entryId: String,
        name: String,
        expectedRevision: Long,
    ): Outcome<Unit> = outcome { rpc.rename(commandId, chatId, entryId, name, expectedRevision) }

    suspend fun renameRecoverable(
        chatId: String,
        parentId: String?,
        entryId: String,
        name: String,
        expectedRevision: Long,
    ): Outcome<GroupFileCommandSubmission> = withOutbox { cache ->
        prepareAndSubmit(cache) {
            PendingGroupFileCommand.rename(
                commandId = newCommandId(),
                chatId = chatId,
                parentId = parentId,
                entryId = entryId,
                name = name,
                expectedRevision = expectedRevision,
                createdAt = nowMillis(),
            )
        }
    }

    suspend fun delete(
        commandId: String,
        chatId: String,
        entryId: String,
        expectedRevision: Long,
    ): Outcome<Unit> = outcome { rpc.delete(commandId, chatId, entryId, expectedRevision) }

    suspend fun deleteRecoverable(
        chatId: String,
        parentId: String?,
        entryId: String,
        expectedRevision: Long,
    ): Outcome<GroupFileCommandSubmission> = withOutbox { cache ->
        prepareAndSubmit(cache) {
            PendingGroupFileCommand.delete(
                commandId = newCommandId(),
                chatId = chatId,
                parentId = parentId,
                entryId = entryId,
                expectedRevision = expectedRevision,
                createdAt = nowMillis(),
            )
        }
    }

    private fun PendingGroupFileCommand.completion(
        status: GroupFileCommandCompletionStatus,
    ): GroupFileCommandCompletion = GroupFileCommandCompletion(
        chatId = chatId,
        entryId = entryId,
        parentId = parentId,
        kind = kind,
        status = status,
    )
}
