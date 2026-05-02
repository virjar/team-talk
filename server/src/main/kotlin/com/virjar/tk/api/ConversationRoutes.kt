package com.virjar.tk.api

import com.virjar.tk.service.ConversationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.conversationRoutes(conversationService: ConversationService) {
    route("/api/v1/conversations") {
        authenticate("auth-jwt") {
            get("/sync") {
                val uid = call.requireUid()
                val version = call.request.queryParameters["version"]?.toLongOrNull() ?: 0L
                val conversations = conversationService.syncConversations(uid, version)
                call.respond(conversations)
            }

            put("/{id}/read") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Long>>()
                val readSeq = body["readSeq"] ?: 0L
                conversationService.markRead(uid, channelId, readSeq)
                call.respond(HttpStatusCode.OK)
            }

            put("/{id}/draft") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, String>>()
                val draft = body["draft"] ?: ""
                conversationService.updateDraft(uid, channelId, draft)
                call.respond(HttpStatusCode.OK)
            }

            put("/{id}/pin") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Boolean>>()
                val pinned = body["pinned"] ?: true
                conversationService.updatePin(uid, channelId, pinned)
                call.respond(HttpStatusCode.OK)
            }

            put("/{id}/mute") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Boolean>>()
                val muted = body["muted"] ?: true
                conversationService.updateMute(uid, channelId, muted)
                call.respond(HttpStatusCode.OK)
            }

            delete("/{id}") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                conversationService.deleteConversation(uid, channelId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
