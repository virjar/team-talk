package com.virjar.tk.api

import com.virjar.tk.dto.*
import com.virjar.tk.service.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.channelRoutes(channelService: ChannelService) {
    route("/api/v1/channels") {
        requireAuth {
            post("/personal") {
                val uid = call.requireUid()
                val body = call.receive<Map<String, String>>()
                val peerUid = body["uid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val channel = channelService.createPersonalChannel(uid, peerUid)
                call.respond(channelService.getChannel(channel.channelId))
            }

            get("/sync") {
                val uid = call.requireUid()
                val version = call.request.queryParameters["version"]?.toLongOrNull() ?: 0L
                val channels = channelService.syncChannels(uid, version)
                call.respond(channels)
            }

            get("/{id}") {
                val channelId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val channel = channelService.getChannel(channelId)
                call.respond(channel)
            }

            post {
                val uid = call.requireUid()
                val req = call.receive<CreateGroupRequest>()
                val channel = channelService.createGroup(uid, req)
                call.respond(HttpStatusCode.Created, channelService.getChannel(channel.channelId))
            }

            put("/{id}") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<UpdateChannelRequest>()
                val channel = channelService.updateChannel(channelId, uid, req)
                call.respond(channel)
            }

            delete("/{id}") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                channelService.deleteChannel(channelId, uid)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/members") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Any?>>()
                val uids = (body["uids"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                channelService.addMembers(channelId, uid, uids)
                call.respond(HttpStatusCode.OK)
            }

            delete("/{id}/members") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Any?>>()
                val uids = (body["uids"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                channelService.removeMembers(channelId, uid, uids)
                call.respond(HttpStatusCode.OK)
            }

            get("/{id}/members") {
                val channelId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val members = channelService.getMembers(channelId, page)
                call.respond(members)
            }

            post("/{id}/transfer") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, String>>()
                val newOwnerUid = body["uid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                channelService.transferOwner(channelId, uid, newOwnerUid)
                call.respond(HttpStatusCode.OK)
            }

            put("/{id}/members/{uid}/role") {
                val operatorUid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val targetUid = call.parameters["uid"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Int>>()
                val role = body["role"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                channelService.setMemberRole(channelId, operatorUid, targetUid, role)
                call.respond(HttpStatusCode.OK)
            }

            // 禁言成员
            put("/{id}/members/{uid}/mute") {
                val operatorUid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val targetUid = call.parameters["uid"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Long>>()
                val durationSeconds = body["durationSeconds"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                channelService.muteMember(channelId, operatorUid, targetUid, durationSeconds)
                call.respond(HttpStatusCode.OK)
            }

            // 解除禁言
            delete("/{id}/members/{uid}/mute") {
                val operatorUid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val targetUid = call.parameters["uid"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                channelService.unmuteMember(channelId, operatorUid, targetUid)
                call.respond(HttpStatusCode.OK)
            }

            // 全员禁言开关
            put("/{id}/mute-all") {
                val operatorUid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<Map<String, Boolean>>()
                val mutedAll = body["mutedAll"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                channelService.setMutedAll(channelId, operatorUid, mutedAll)
                call.respond(HttpStatusCode.OK)
            }

            // ── 邀请链接 ──

            // 创建邀请链接
            post("/{id}/invite-links") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<CreateInviteLinkRequest>()
                val link = channelService.createInviteLink(channelId, uid, req.name, req.maxUses, req.expiresIn)
                val host = call.request.host()
                val port = call.request.port()
                val scheme = call.request.local.scheme
                val baseUrl = if (port == 80 || port == 443) "$scheme://$host" else "$scheme://$host:$port"
                call.respond(
                    HttpStatusCode.Created,
                    InviteLinkDto(
                        token = link.token,
                        channelId = link.channelId,
                        creatorUid = link.creatorUid,
                        name = link.name,
                        maxUses = link.maxUses,
                        useCount = link.useCount,
                        expiresAt = link.expiresAt,
                        createdAt = link.createdAt,
                        url = "$baseUrl/api/v1/channels/invite/${link.token}/info",
                    )
                )
            }

            // 列出邀请链接
            get("/{id}/invite-links") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val links = channelService.getInviteLinks(channelId, uid)
                val host = call.request.host()
                val port = call.request.port()
                val scheme = call.request.local.scheme
                val baseUrl = if (port == 80 || port == 443) "$scheme://$host" else "$scheme://$host:$port"
                call.respond(links.map { link ->
                    InviteLinkDto(
                        token = link.token,
                        channelId = link.channelId,
                        creatorUid = link.creatorUid,
                        name = link.name,
                        maxUses = link.maxUses,
                        useCount = link.useCount,
                        expiresAt = link.expiresAt,
                        createdAt = link.createdAt,
                        url = "$baseUrl/api/v1/channels/invite/${link.token}/info",
                    )
                })
            }

            // 撤销邀请链接
            delete("/{id}/invite-links/{token}") {
                val uid = call.requireUid()
                val channelId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                channelService.revokeInviteLink(channelId, token, uid)
                call.respond(HttpStatusCode.OK)
            }
        }

        // ── 通过邀请链接加入（需登录） ──
        requireAuth {
            post("/invite/{token}/join") {
                val uid = call.requireUid()
                val token = call.parameters["token"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val (channel, joined) = channelService.joinByInviteLink(token, uid)
                call.respond(
                    JoinByLinkResultDto(
                        joined = joined,
                        channelId = channel.channelId,
                        channelName = channel.name,
                    )
                )
            }

            // 链接预览信息
            get("/invite/{token}/info") {
                val token = call.parameters["token"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val (channelName, channelId, memberCount) = channelService.getInviteLinkInfo(token)
                val link = com.virjar.tk.db.InviteLinkDao.findByToken(token)
                call.respond(
                    InviteLinkInfoDto(
                        channelId = channelId,
                        channelName = channelName,
                        memberCount = memberCount,
                        expiresAt = link?.expiresAt,
                    )
                )
            }
        }
    }
}
