package com.virjar.tk.repository

import com.virjar.tk.client.UserContext
import com.virjar.tk.database.LocalCache
import com.virjar.tk.dto.MessageSearchResponse
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.util.AppLog
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ChatRepository(private val ctx: UserContext) {

    private val localCache: LocalCache get() = ctx.localCache

    /**
     * 先本地后网络策略：
     * 1. 从本地 DB 获取缓存消息
     * 2. 发起 HTTP 请求获取增量消息
     * 3. 增量消息写入本地 DB
     * 4. 返回合并后的完整消息列表
     */
    suspend fun getMessages(channelId: String, afterSeq: Long = 0, limit: Int = 50): List<Message> {
        return try {
            // 从本地 DB 获取当前最大 seq
            val localMaxSeq = withContext(Dispatchers.IO) { localCache.getMaxSeq(channelId) }

            // 如果本地无数据或显式指定 afterSeq=0（首次加载），走 HTTP 全量拉取
            if (localMaxSeq == 0L || afterSeq == 0L) {
                AppLog.i("ChatRepo", "getMessages HTTP GET: channelId=$channelId fullFetch")
                val response = ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/$channelId/messages?afterSeq=$afterSeq&limit=$limit") {
                    header("Authorization", ctx.authHeader())
                }
                val jsonList = response.body<List<JsonObject>>()
                AppLog.i("ChatRepo", "getMessages HTTP response: count=${jsonList.size} channelId=$channelId")
                val messages = jsonList.mapNotNull { Message.fromJson(it) }
                // 写入本地 DB
                withContext(Dispatchers.IO) { localCache.insertMessagesFromJson(jsonList) }
                messages
            } else {
                // 本地有数据，获取增量
                AppLog.i("ChatRepo", "getMessages incremental: channelId=$channelId localMaxSeq=$localMaxSeq")
                val response = ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/$channelId/messages?afterSeq=$localMaxSeq&limit=$limit") {
                    header("Authorization", ctx.authHeader())
                }
                val jsonList = response.body<List<JsonObject>>()
                val incremental = jsonList.mapNotNull { Message.fromJson(it) }
                AppLog.i("ChatRepo", "getMessages incremental HTTP: count=${incremental.size} channelId=$channelId")
                // 增量写入 DB
                withContext(Dispatchers.IO) { localCache.insertMessagesFromJson(jsonList) }
                // 读取完整的本地消息（包含已合并的增量）
                withContext(Dispatchers.IO) { localCache.getMessages(channelId, limit) }
            }
        } catch (e: Exception) {
            AppLog.e("ChatRepo", "getMessages failed: channelId=$channelId", e)
            // 网络失败时，尝试返回本地缓存
            val local = withContext(Dispatchers.IO) { localCache.getMessages(channelId, limit) }
            if (local.isNotEmpty()) local else throw e
        }
    }

    /** Send text message via TCP, returns server-assigned messageId */
    suspend fun sendTextMessage(channelId: String, channelType: ChannelType, text: String): String =
        ctx.sendMessage(channelId, channelType, text)

    /** Send image message via TCP, returns server-assigned messageId */
    suspend fun sendImageMessage(channelId: String, channelType: ChannelType, url: String, width: Int, height: Int, size: Long): String {
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
    ): String {
        val body = ReplyBody(
            replyToMessageId, replyToSenderUid, replyToSenderName,
            replyToMessageType.toByte(), text, emptyList())
        return ctx.sendMessage(channelId, channelType, body)
    }

    /** Send file message via TCP */
    suspend fun sendFileMessage(channelId: String, channelType: ChannelType, url: String, fileName: String, fileSize: Long): String {
        return ctx.sendMessage(channelId, channelType, FileBody(url, fileName, fileSize, null, null))
    }

    /** Send video message via TCP */
    suspend fun sendVideoMessage(channelId: String, channelType: ChannelType, url: String, width: Int, height: Int, size: Long, duration: Int, coverUrl: String): String {
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
    suspend fun sendVoiceMessage(channelId: String, channelType: ChannelType, url: String, duration: Int, size: Long): String {
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
    ): String {
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
     * 向前加载历史消息：获取 channelId 中 seq < beforeSeq 的消息。
     * 策略：先从本地 DB 取，如果本地不足再走 HTTP API 补充。
     * 返回结果按 seq 升序排列（旧 → 新）。
     */
    suspend fun getMessagesBefore(channelId: String, beforeSeq: Long, limit: Int = 50): List<Message> {
        return try {
            // 先从本地 DB 获取
            val localMessages = withContext(Dispatchers.IO) {
                localCache.getMessagesBeforeSeq(channelId, beforeSeq, limit)
            }
            AppLog.i("ChatRepo", "getMessagesBefore local: channelId=$channelId beforeSeq=$beforeSeq count=${localMessages.size}")

            if (localMessages.size >= limit) {
                // 本地数据充足，直接返回
                localMessages
            } else {
                // 本地不足，从服务端拉取补充
                val response = ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/$channelId/messages?beforeSeq=$beforeSeq&limit=$limit") {
                    header("Authorization", ctx.authHeader())
                }
                val jsonList = response.body<List<JsonObject>>()
                val serverMessages = jsonList.mapNotNull { Message.fromJson(it) }
                AppLog.i("ChatRepo", "getMessagesBefore HTTP: channelId=$channelId beforeSeq=$beforeSeq count=${serverMessages.size}")

                // 写入本地 DB
                if (jsonList.isNotEmpty()) {
                    withContext(Dispatchers.IO) { localCache.insertMessagesFromJson(jsonList) }
                }
                serverMessages
            }
        } catch (e: Exception) {
            AppLog.e("ChatRepo", "getMessagesBefore failed: channelId=$channelId beforeSeq=$beforeSeq", e)
            // 网络失败时，尝试返回本地缓存
            withContext(Dispatchers.IO) { localCache.getMessagesBeforeSeq(channelId, beforeSeq, limit) }
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
