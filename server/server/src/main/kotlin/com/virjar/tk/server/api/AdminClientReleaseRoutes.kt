package com.virjar.tk.server.api

import com.virjar.tk.server.infra.clientrelease.ClientReleaseConflictException
import com.virjar.tk.server.infra.clientrelease.ClientReleaseService
import com.virjar.tk.server.infra.clientrelease.ClientReleaseValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * 管理台的客户端发布端点（挂在 /api/admin 鉴权域内；发布上传本体在公开路由
 * POST /api/v1/client/releases，那里同时接受管理会话与 CI 发布令牌）。
 */
internal fun Route.adminClientReleaseRoutes(service: ClientReleaseService) {
    route("/client-releases") {
        get {
            val q = call.request.queryParameters
            val releases = run {
                service.listReleases(
                    clientType = q["client"]?.takeIf { it.isNotBlank() },
                    status = q["status"]?.takeIf { it.isNotBlank() },
                    limit = 100,
                    offset = (q["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0),
                )
            }
            call.respond(mapOf("releases" to releases))
        }
        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid release id"))
            val info = run { service.releaseInfo(id) }
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "release not found"))
            call.respond(info)
        }
        post("/{id}/disable") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid release id"))
            val req = call.receiveBoundedJsonOrRespond<DisableRequest>() ?: return@post
            call.respondMutation {
                service.disableRelease(call.requireAdminPrincipal(), id, req.fallbackReleaseId)
            }
        }
        post("/{id}/enable") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid release id"))
            call.respondMutation { service.enableRelease(call.requireAdminPrincipal(), id) }
        }
        delete("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid release id"))
            call.respondMutation { service.deleteRelease(call.requireAdminPrincipal(), id) }
        }
    }

    route("/client-channels") {
        get {
            val client = call.request.queryParameters["client"]?.takeIf { it.isNotBlank() }
            val channels = run { service.listChannels(client) }
            call.respond(mapOf("channels" to channels))
        }
        put("/{clientType}/{platform}/{arch}/{channel}") {
            val req = call.receiveBoundedJsonOrRespond<SetChannelRequest>() ?: return@put
            call.respondMutation {
                service.setChannel(
                    actor = call.requireAdminPrincipal(),
                    clientType = call.parameters["clientType"]!!,
                    platform = call.parameters["platform"]!!,
                    arch = call.parameters["arch"]!!,
                    channel = call.parameters["channel"]!!,
                    releaseId = req.releaseId,
                )
            }
        }
        post("/{clientType}/{platform}/{arch}/{channel}/enabled") {
            val req = call.receiveBoundedJsonOrRespond<SetEnabledRequest>() ?: return@post
            call.respondMutation {
                service.setChannelEnabled(
                    actor = call.requireAdminPrincipal(),
                    clientType = call.parameters["clientType"]!!,
                    platform = call.parameters["platform"]!!,
                    arch = call.parameters["arch"]!!,
                    channel = call.parameters["channel"]!!,
                    enabled = req.enabled,
                )
            }
        }
    }
}

@Serializable
private data class DisableRequest(val fallbackReleaseId: Long? = null)

@Serializable
private data class SetChannelRequest(val releaseId: Long? = null)

@Serializable
private data class SetEnabledRequest(val enabled: Boolean)

/** 管理动作的统一错误面：校验失败 400、冲突 409，成功一律 {"ok": true}。 */
private suspend inline fun io.ktor.server.application.ApplicationCall.respondMutation(
    crossinline block: () -> Unit,
) {
    try {
        run { block() }
        respond(mapOf("ok" to true))
    } catch (validation: ClientReleaseValidationException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to (validation.message ?: "invalid request")))
    } catch (conflict: ClientReleaseConflictException) {
        respond(HttpStatusCode.Conflict, mapOf("error" to (conflict.message ?: "release conflict")))
    }
}
