package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.ChatDraftCommand
import com.virjar.tk.protocol.model.ChatDraftMutationResult
import com.virjar.tk.protocol.model.ChatDraftSnapshot as SharedChatDraftSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** 当前完整本机草稿的同步状态；冲突候选只属于这个账号和 chat。 */
data class ChatDraftSyncState(
    val remote: SharedChatDraftSnapshot? = null,
    val pending: Boolean = false,
    val conflict: Boolean = false,
    val failure: String? = null,
    val assetsAvailable: Boolean = true,
)

@Serializable
data class PendingSharedChatDraft(val command: ChatDraftCommand, val localRevision: Long)

/** 同步 API 仅在 storage dispatcher 使用；网络及 UI 都不直接写这些记录。 */
interface LocalChatDraftSync {
    val changes: StateFlow<Long>
    fun state(chatId: String): ChatDraftSyncState
    fun ensure(chatId: String)
    fun invalidate(chatId: String, revision: Long)
    fun invalidateAll()
    fun refreshTargets(): List<String>
    fun readFailed(chatId: String, reason: String)
    fun applyRemote(snapshot: SharedChatDraftSnapshot): Boolean
    fun nextCommand(chatId: String, now: Long): PendingSharedChatDraft?
    fun workChats(): List<String>
    fun acknowledge(pending: PendingSharedChatDraft, result: ChatDraftMutationResult)
    fun fail(pending: PendingSharedChatDraft, reason: String)
    fun resolve(chatId: String, keepLocal: Boolean)
    fun nextExpiryAt(): Long?
}
