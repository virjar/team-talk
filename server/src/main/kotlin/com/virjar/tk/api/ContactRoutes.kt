package com.virjar.tk.api

import com.virjar.tk.dto.*
import com.virjar.tk.service.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.contactRoutes(friendService: FriendService) {
    route("/api/v1/contacts") {
        authenticate("auth-jwt") {
            get {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val version = call.request.queryParameters["version"]?.toLongOrNull() ?: 0L
                val friends = friendService.getFriends(uid, version)
                call.respond(friends)
            }

            post("/apply") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receive<ApplyFriendRequest>()
                val result = friendService.apply(uid, req)
                call.respond(HttpStatusCode.Created, result)
            }

            post("/accept") {
                val body = call.receive<Map<String, String>>()
                val token = body["token"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val result = friendService.accept(token)
                call.respond(result)
            }

            put("/{uid}/remark") {
                val callerUid = call.principal<JWTPrincipal>()!!.payload.subject
                val friendUid = call.parameters["uid"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, String>>()
                val remark = body["remark"] ?: ""
                friendService.updateRemark(callerUid, friendUid, remark)
                call.respond(HttpStatusCode.OK)
            }

            delete("/{uid}") {
                val callerUid = call.principal<JWTPrincipal>()!!.payload.subject
                val friendUid = call.parameters["uid"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                friendService.deleteFriend(callerUid, friendUid)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/applies") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val applies = friendService.getApplies(uid, page)
                call.respond(applies)
            }
        }
    }

    // ── Blacklist ──
    route("/api/v1/blacklist") {
        authenticate("auth-jwt") {
            get {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val blacklist = friendService.getBlacklist(uid)
                call.respond(blacklist)
            }
            post("/{uid}") {
                val callerUid = call.principal<JWTPrincipal>()!!.payload.subject
                val targetUid = call.parameters["uid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                friendService.addBlacklist(callerUid, targetUid)
                call.respond(HttpStatusCode.OK)
            }
            delete("/{uid}") {
                val callerUid = call.principal<JWTPrincipal>()!!.payload.subject
                val targetUid = call.parameters["uid"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                friendService.removeBlacklist(callerUid, targetUid)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
