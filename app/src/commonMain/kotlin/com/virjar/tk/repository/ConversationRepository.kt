package com.virjar.tk.repository

import com.virjar.tk.client.UserContext
import com.virjar.tk.database.LocalCache
import com.virjar.tk.dto.ConversationDto
import com.virjar.tk.util.AppLog
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class ConversationRepository(private val ctx: UserContext) {

    private val localCache: LocalCache get() = ctx.localCache

    /** 从本地 DB 读取会话列表，不进行网络调用。 */
    suspend fun getCachedConversations(): List<ConversationDto> {
        return withContext(Dispatchers.IO) { localCache.getAllConversations() }
    }

    /**
     * 先网络后缓存策略：HTTP 获取后写入 localCache，返回服务端结果。
     * 网络失败时回退到本地缓存。
     */
    suspend fun syncConversations(version: Long = 0): List<ConversationDto> {
        return try {
            val conversations = ctx.httpClient.get("${ctx.baseUrl}/api/v1/conversations/sync?version=$version") {
                header("Authorization", ctx.authHeader())
            }.body<List<ConversationDto>>()
            // 写入本地 DB
            withContext(Dispatchers.IO) { localCache.insertConversations(conversations) }
            conversations
        } catch (e: Exception) {
            AppLog.e("ConvRepo", "syncConversations failed", e)
            // 网络失败时，返回本地缓存
            val local = withContext(Dispatchers.IO) { localCache.getAllConversations() }
            if (local.isNotEmpty()) local else throw e
        }
    }

    suspend fun markRead(channelId: String, readSeq: Long) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/conversations/$channelId/read") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("readSeq" to readSeq))
        }
        // 同步更新本地：清零未读 + 记录已读位置
        withContext(Dispatchers.IO) {
            localCache.updateUnreadCount(channelId, 0)
            localCache.updateReadSeq(channelId, readSeq)
        }
    }

    /** 草稿是纯本地状态：只写 localCache，不发网络 */
    fun updateDraft(channelId: String, draft: String) {
        localCache.updateDraft(channelId, draft)
    }

    fun clearExpiredDrafts() {
        localCache.clearExpiredDrafts()
    }

    suspend fun updatePin(channelId: String, pinned: Boolean) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/conversations/$channelId/pin") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("pinned" to pinned))
        }
        withContext(Dispatchers.IO) { localCache.updatePin(channelId, pinned) }
    }

    suspend fun updateMute(channelId: String, muted: Boolean) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/conversations/$channelId/mute") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("muted" to muted))
        }
        withContext(Dispatchers.IO) { localCache.updateMute(channelId, muted) }
    }

    suspend fun deleteConversation(channelId: String) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/conversations/$channelId") {
            header("Authorization", ctx.authHeader())
        }
        withContext(Dispatchers.IO) { localCache.deleteConversation(channelId) }
    }
}
