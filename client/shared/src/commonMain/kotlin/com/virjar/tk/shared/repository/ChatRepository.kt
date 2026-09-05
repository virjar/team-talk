package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingGroupCreationCommand
import com.virjar.tk.shared.client.PendingInviteLinkCreation
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.InviteLink
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.gen.ChatRpcProxy
import java.util.UUID

class ChatRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
    private val newGroupOperationId: () -> String = { UUID.randomUUID().toString() },
    private val newInviteOperationId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ownerUid: String? = null,
    private val onPendingReliableCommandCommitted: () -> Unit = {},
    private val onPendingInviteLinkCreationRecovered: (chatId: String) -> Unit = {},
) {
    private val rpc = ChatRpcProxy(rpcClient)

    suspend fun createPersonalChat(targetUid: String): Outcome<Chat> = outcome {
        rpc.createPersonal(targetUid).also(localCache::upsertChat)
    }

    /** 幂等取回（必要时创建）当前用户的"保存的消息"私有会话。 */
    suspend fun getOrCreateSavedChat(): Outcome<Chat> = outcome {
        rpc.getOrCreateSavedChat().also(localCache::upsertChat)
    }

    suspend fun createGroup(
        operationId: String,
        name: String,
        avatar: String? = null,
        memberUids: List<String>,
    ): Outcome<Chat> = outcome {
        rpc.createGroup(operationId, name, avatar, memberUids).also(localCache::upsertChat)
    }

    /** 返回部署/账号作用域的 GUI 命令，且不执行任何网络操作。 */
    fun getPendingGroupCreation(): PendingGroupCreationCommand? =
        localCache.getPendingGroupCreation()?.also { command ->
            ownerUid?.let { expected ->
                check(command.creatorUid == expected) { "Pending group command owner mismatch" }
            }
        }

    /**
     * GUI 创建边界：在 RPC 之前冻结并持久化完整的规范化命令。
     *
     * 语义相等的已恢复 payload 复用其 operation ID。显式变更的输入会原子地
     * 用一个新 ID 替换本地唯一的槽位。网络、超时、解码和本地投影失败都会保留该命令；
     * 只有成功的 RPC 响应才有条件地清除那一代精确记录。
     */
    suspend fun createRecoverableGroup(
        name: String,
        avatar: String? = null,
        memberUids: List<String>,
    ): Outcome<Chat> = outcome {
        val creatorUid = requireNotNull(ownerUid) {
            "Recoverable GUI group creation requires a fixed account owner"
        }
        val candidate = PendingGroupCreationCommand.create(
            operationId = newGroupOperationId(),
            creatorUid = creatorUid,
            name = name,
            avatar = avatar,
            memberUids = memberUids,
        )
        val command = getPendingGroupCreation()
            ?.takeIf { it.hasSamePayload(candidate) }
            ?: candidate

        // 同步 SQLite 提交就是持久化屏障。UI 调用方把整个用例放在
        // UiLocalDataBoundary 之后，因此 Compose/Main 永远不会阻塞在存储上。
        localCache.replacePendingGroupCreation(command)
        val chat = rpc.createGroup(
            operationId = command.operationId,
            name = command.name,
            avatar = command.avatar,
            memberUids = command.memberUids,
        )
        localCache.upsertChat(chat)
        localCache.clearPendingGroupCreation(command.operationId)
        chat
    }

    /** 显式放弃操作；迟到的旧成功无法清除一个更新的替代命令。 */
    fun discardPendingGroupCreation(operationId: String): Boolean =
        localCache.clearPendingGroupCreation(operationId)

    suspend fun getChat(chatId: String): Outcome<Chat?> = outcome {
        val lease = localCache.beginChatSnapshot(chatId)
        try {
            val remote = rpc.get(chatId)
            localCache.applyChatSnapshot(lease, remote)
            // 一条通知、墓碑、重置或更新的请求都可能把这笔 RPC 挡在栅栏外。返回
            // 收敛后的本地事实（包括 null），而不是被拒绝的线上响应。
            localCache.getChat(chatId)
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    suspend fun getMembers(chatId: String): Outcome<List<Member>> = outcome {
        val lease = localCache.beginMemberSnapshot(chatId)
        try {
            val remote = rpc.getMembers(chatId)
            localCache.applyMemberSnapshot(lease, remote)
            // 被拒绝的过期快照仍会解析到当前本地的成员投影。
            localCache.getMembers(chatId)
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    suspend fun dissolveGroup(chatId: String): Outcome<Unit> = outcome {
        rpc.delete(chatId)
        localCache.deleteChat(chatId)
    }

    suspend fun leaveGroup(chatId: String): Outcome<Unit> = outcome {
        rpc.leaveGroup(chatId)
        localCache.deleteChat(chatId)
    }

    suspend fun addMembers(chatId: String, uids: List<String>): Outcome<Unit> = outcome { rpc.addMembers(chatId, uids) }

    suspend fun createInviteLink(
        chatId: String,
        name: String = "",
        maxUses: Int = 0,
        expiresAt: Long = 0,
    ): Outcome<String> = outcome {
        val pending = localCache.preparePendingInviteLinkCreation(
            PendingInviteLinkCreation(
                operationId = newInviteOperationId(),
                chatId = chatId,
                name = name.trim(),
                maxUses = maxUses,
                expiresAt = expiresAt,
                createdAt = nowMillis(),
            ),
        )
        onPendingReliableCommandCommitted()
        sendPendingInviteLinkCreation(pending)
    }

    private suspend fun sendPendingInviteLinkCreation(
        pending: PendingInviteLinkCreation,
    ): String {
        val token = try {
            rpc.createInviteLink(
                operationId = pending.operationId,
                issuedAt = pending.createdAt,
                chatId = pending.chatId,
                name = pending.name,
                maxUses = pending.maxUses,
                expiresAt = pending.expiresAt,
            )
        } catch (failure: Exception) {
            if (failure.isDefinitiveReliableCommandRejection()) {
                localCache.clearPendingInviteLinkCreation(pending.operationId)
            }
            throw failure
        }
        val acknowledged = localCache.clearPendingInviteLinkCreation(pending.operationId)
        if (acknowledged) {
            // token 保留在前台直接结果中；这个重放提示只包含投影键，
            // 保留下来是安全的，供稍后构建的功能使用。
            onPendingInviteLinkCreationRecovered(pending.chatId)
        }
        return token
    }

    internal suspend fun retryPendingInviteLinkCreations(): Outcome<Unit> = retryPendingMirrors(
        snapshot = localCache.getPendingInviteLinkCreations(),
    ) { pending ->
        outcome {
            sendPendingInviteLinkCreation(pending)
        }
    }

    fun discardPendingInviteLinkCreation(operationId: String): Boolean =
        localCache.clearPendingInviteLinkCreation(operationId)

    suspend fun listInviteLinks(chatId: String): Outcome<List<InviteLink>> = outcome { rpc.listInviteLinks(chatId) }
    suspend fun revokeInviteLink(token: String): Outcome<Unit> = outcome { rpc.revokeInviteLink(token) }

    suspend fun updateGroup(chatId: String, name: String? = null, avatar: String? = null, notice: String? = null): Outcome<Unit> = outcome {
        rpc.update(chatId, name, avatar, notice)
    }

    suspend fun removeMember(chatId: String, memberUid: String): Outcome<Unit> = outcome {
        rpc.removeMembers(chatId, memberUid)
        localCache.removeMember(chatId, memberUid)
    }

    suspend fun muteMember(chatId: String, memberUid: String, durationSeconds: Int): Outcome<Unit> = outcome { rpc.muteMember(chatId, memberUid, durationSeconds) }
    suspend fun unmuteMember(chatId: String, memberUid: String): Outcome<Unit> = outcome { rpc.unmuteMember(chatId, memberUid) }
    suspend fun setMemberRole(chatId: String, memberUid: String, role: Int): Outcome<Unit> = outcome { rpc.setRole(chatId, memberUid, role) }
}
