package com.virjar.tk.repository

import com.virjar.tk.client.SendResult
import com.virjar.tk.client.UserContext
import com.virjar.tk.database.LocalCache
import com.virjar.tk.dto.MessageSearchResponse
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.util.AppLog
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

data class HistoryResult(val messages: List<Message>, val hasMore: Boolean)

class ChatRepository(private val ctx: UserContext) {

    private val localCache: LocalCache get() = ctx.localCache

    /**
     * 加载消息：仅从本地 DB 读取。TCP SUBSCRIBE 负责填充数据。
     * 首次加载（本地为空）返回空列表，由调用方触发 SUBSCRIBE 后消息异步到达。
     */
    suspend fun getMessages(channelId: String, limit: Int = 50): List<Message> {
        return withContext(Dispatchers.IO) {
            localCache.getMessages(channelId, limit)
        }
    }

    /** Send text message via TCP, returns server-assigned messageId */
    suspend fun sendTextMessage(channelId: String, channelType: ChannelType, text: String): SendResult =
        ctx.sendMessage(channelId, channelType, text)

    /** Send image message via TCP, returns server-assigned messageId */
    suspend fun sendImageMessage(channelId: String, channelType: ChannelType, url: String, width: Int, height: Int, size: Long): SendResult {
        return ctx.sendMessage(channelId, channelType, ImageBody(url, width, height, size, null, null))
    }

    /** Send reply message via TCP */
    suspend fun sendReplyMessage(
        channelId: String,
        channelType: ChannelType,
        text: String,
        replyToMessageId: String,
        replyToSenderUid: String,
        replyToSenderName: String,
        replyToMessageType: Int,
    ): SendResult {
        val body = ReplyBody(
            replyToMessageId, replyToSenderUid, replyToSenderName,
            replyToMessageType.toByte(), text, emptyList())
        return ctx.sendMessage(channelId, channelType, body)
    }

    /** Send file message via TCP */
    suspend fun sendFileMessage(channelId: String, channelType: ChannelType, url: String, fileName: String, fileSize: Long): SendResult {
        return ctx.sendMessage(channelId, channelType, FileBody(url, fileName, fileSize, null, null))
    }

    /** Send video message via TCP */
    suspend fun sendVideoMessage(channelId: String, channelType: ChannelType, url: String, width: Int, height: Int, size: Long, duration: Int, coverUrl: String): SendResult {
        return ctx.sendMessage(channelId, channelType, VideoBody(url, width, height, size, duration, coverUrl))
    }

    suspend fun revokeMessage(channelId: String, seq: Long) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/channels/$channelId/messages/$seq/revoke") {
            header("Authorization", ctx.authHeader())
        }
    }

    suspend fun editMessage(channelId: String, seq: Long, newText: String) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/channels/$channelId/messages/$seq/edit") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("newText" to newText))
        }
    }

    /** Send voice message via TCP */
    suspend fun sendVoiceMessage(channelId: String, channelType: ChannelType, url: String, duration: Int, size: Long): SendResult {
        return ctx.sendMessage(channelId, channelType, VoiceBody(url, duration, size, null))
    }

    /** Send forward message via TCP */
    suspend fun sendForwardMessage(
        channelId: String,
        channelType: ChannelType,
        forwardFromChannelId: String?,
        forwardFromMessageId: String,
        forwardFromSenderUid: String?,
        forwardFromSenderName: String?,
        forwardPacketType: Byte,
        forwardPayload: String?,
    ): SendResult {
        val body = ForwardBody(forwardFromChannelId, forwardFromMessageId, forwardFromSenderUid,
            forwardFromSenderName, forwardPacketType, forwardPayload)
        return ctx.sendMessage(channelId, channelType, body)
    }

    /** Delete a single message (local delete) */
    suspend fun deleteMessage(messageId: String, channelId: String, channelType: ChannelType, seq: Long) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/message") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(listOf(mapOf(
                "messageId" to messageId,
                "messageSeq" to seq,
                "channelId" to channelId,
                "channelType" to channelType.code,
            )))
        }
        // 同步删除本地缓存
        withContext(Dispatchers.IO) { localCache.deleteMessage(messageId) }
    }

    /** Mark a message as locally deleted (client-side only, no server API call). */
    suspend fun deleteMessageLocal(messageId: String) {
        withContext(Dispatchers.IO) { localCache.markLocalDeleted(messageId) }
    }

    /**
     * 向前加载历史消息：先查本地 DB，不足时通过 TCP HISTORY_LOAD 从服务端拉取。
     * 服务端推送的消息通过 onMessageReceived 自动写入本地 DB。
     */
    suspend fun getMessagesBefore(channelId: String, beforeSeq: Long, limit: Int = 50): HistoryResult {
        return try {
            // 先从本地 DB 获取
            val localMessages = withContext(Dispatchers.IO) {
                localCache.getMessagesBeforeSeq(channelId, beforeSeq, limit)
            }
            if (localMessages.size >= limit) {
                HistoryResult(localMessages, true)
            } else {
                // 本地不足，通过 TCP HISTORY_LOAD 拉取
                val result = ctx.loadHistory(channelId, beforeSeq, limit)
                // 消息已通过 onMessageReceived 写入本地 DB，重新读取
                val messages = withContext(Dispatchers.IO) {
                    localCache.getMessagesBeforeSeq(channelId, beforeSeq, limit)
                }
                HistoryResult(messages, result.hasMore)
            }
        } catch (e: Exception) {
            AppLog.e("ChatRepo", "getMessagesBefore failed: channelId=$channelId beforeSeq=$beforeSeq", e)
            val localMessages = withContext(Dispatchers.IO) {
                localCache.getMessagesBeforeSeq(channelId, beforeSeq, limit)
            }
            HistoryResult(localMessages, localMessages.size >= limit)
        }
    }

    /** Search messages via server full-text search API */
    suspend fun searchMessages(
        query: String,
        channelId: String? = null,
        senderUid: String? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): MessageSearchResponse {
        val response = ctx.httpClient.get("${ctx.baseUrl}/api/v1/messages/search") {
            header("Authorization", ctx.authHeader())
            parameter("q", query)
            if (channelId != null) parameter("channelId", channelId)
            if (senderUid != null) parameter("senderUid", senderUid)
            if (startTimestamp != null) parameter("startTimestamp", startTimestamp)
            if (endTimestamp != null) parameter("endTimestamp", endTimestamp)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        return response.body<MessageSearchResponse>()
    }
}
