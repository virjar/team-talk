package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.shared.database.AppDatabaseQueries
import kotlinx.coroutines.flow.Flow

/**
 * 群头像的单一持久投影。SQL 与发布共用缓存状态锁；首次收集可离线读取，关闭完成收集器。
 * 已缓存头像只作为旧投影，本次会话的权威事件/RPC 才抑制重复懒加载。
 */
internal class LocalChatAvatarProjectionStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: PlatformLock,
) {
    private val resolvedChatIds = linkedSetOf<String>()
    private val projection: RetirableProjectionState<Map<String, Attachment>>

    init {
        queries.deleteChatAvatarLru()
        projection = RetirableProjectionState(readPersisted())
    }

    fun observe(): Flow<Map<String, Attachment>> = cacheUseGate.use { projection.observe() }

    fun isResolved(chatId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) { chatId in resolvedChatIds }
    }

    fun upsert(chatId: String, attachment: Attachment?) = cacheUseGate.use {
        synchronized(stateLock) {
            queries.transaction {
                if (attachment == null) queries.deleteChatAvatar(chatId)
                else queries.upsertChatAvatar(chatId, ProtoCodec.encode(attachment), platformCurrentTimeMillis())
                queries.deleteChatAvatarLru()
            }
            resolvedChatIds.remove(chatId)
            resolvedChatIds.add(chatId)
            if (resolvedChatIds.size > ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER) {
                resolvedChatIds.remove(resolvedChatIds.first())
            }
            projection.value = readPersisted()
        }
    }

    /** 事务中的 chat_avatar 行已删除；只发布内存结果。调用方持有 stateLock。 */
    fun removeChatLocked(chatId: String) {
        resolvedChatIds.remove(chatId)
        projection.value = projection.value - chatId
    }

    /** 所有持久头像已在 reset 事务中删除；调用方持有 stateLock。 */
    fun clearLocked() {
        resolvedChatIds.clear()
        projection.value = emptyMap()
    }

    fun closeLocked() {
        resolvedChatIds.clear()
        projection.retire(emptyMap())
    }

    private fun readPersisted(): Map<String, Attachment> = queries.selectAllChatAvatars()
        .executeAsList().associate { row -> row.chat_id to ProtoCodec.decode(Attachment, row.payload) }
}
