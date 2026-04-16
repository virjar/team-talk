package com.virjar.tk.api

import com.virjar.tk.dto.*
import com.virjar.tk.service.MessageDeliveryService
import com.virjar.tk.service.MessageService
import com.virjar.tk.service.SearchIndex
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Routing.messageRoutes(messageService: MessageService, searchIndex: SearchIndex, deliveryService: MessageDeliveryService) {
    route("/api/v1/channels/{id}/messages") {
        authenticate("auth-jwt") {
            post("/sync") {
                val channelId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<SyncMessagesRequest>()
                val messages = messageService.syncMessages(channelId, req)
                call.respond(messages)
            }

            get {
                val channelId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val afterSeq = call.request.queryParameters["afterSeq"]?.toLongOrNull() ?: 0L
                val beforeSeq = call.request.queryParameters["beforeSeq"]?.toLongOrNull() ?: 0L
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val messages = messageService.getMessages(channelId, afterSeq, beforeSeq, limit)
                call.respond(messages)
            }

            delete("/{seq}/revoke") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val channelId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val seq = call.parameters["seq"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                messageService.revokeMessage(channelId, seq, uid)
                call.respond(HttpStatusCode.OK)
            }

            put("/{seq}/edit") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val seq = call.parameters["seq"]?.toLongOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<EditMessageRequest>()
                val updated = messageService.editMessage(channelId, seq, body.newText, uid)
                deliveryService.deliverEdit(updated)
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    // 全局消息搜索
    route("/api/v1/messages/search") {
        authenticate("auth-jwt") {
            get {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val q = call.request.queryParameters["q"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError(message = "q parameter required"))

                val channelId = call.request.queryParameters["channelId"]
                val senderUid = call.request.queryParameters["senderUid"]
                val startTimestamp = call.request.queryParameters["startTimestamp"]?.toLongOrNull()
                val endTimestamp = call.request.queryParameters["endTimestamp"]?.toLongOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                // 权限过滤：通过 MessageService 获取用户可访问的频道 ID
                val userChannelIds = messageService.getUserChannelIds(uid)
                if (userChannelIds.isEmpty()) {
                    call.respond(MessageSearchResponse(0, emptyList()))
                    return@get
                }

                // 如果指定了 channelId，额外校验 isMember
                val searchChannelIds = if (channelId != null) {
                    if (channelId !in userChannelIds) {
                        call.respond(MessageSearchResponse(0, emptyList()))
                        return@get
                    }
                    setOf(channelId)
                } else {
                    userChannelIds
                }

                val (total, results) = searchIndex.search(
                    q = q,
                    channelIds = searchChannelIds,
                    senderUid = senderUid,
                    startTimestamp = startTimestamp,
                    endTimestamp = endTimestamp,
                    limit = limit,
                    offset = offset,
                )

                // 批量预加载频道名称
                val channelNames = mutableMapOf<String, String>()
                for (sr in results) {
                    if (sr.channelId !in channelNames) {
                        channelNames[sr.channelId] = messageService.getChannelName(sr.channelId)
                    }
                }

                val items = results.mapNotNull { sr ->
                    val message = messageService.getMessageBySeq(sr.channelId, sr.seq) ?: return@mapNotNull null
                    val senderName = messageService.getUserName(sr.senderUid)
                    MessageSearchResult(
                        messageId = sr.messageId,
                        channelId = sr.channelId,
                        channelType = sr.messageType,
                        channelName = channelNames[sr.channelId] ?: "",
                        senderUid = sr.senderUid,
                        senderName = senderName,
                        messageType = sr.messageType,
                        seq = sr.seq,
                        timestamp = sr.timestamp,
                        highlight = sr.highlight,
                        body = message.body.toJson().toString(),
                    )
                }

                call.respond(MessageSearchResponse(total, items))
            }
        }
    }
}
