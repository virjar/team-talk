package com.virjar.tk.server.api

import com.virjar.tk.server.domain.message.ServiceAccountMessages
import com.virjar.tk.server.runtime.ServiceBroadcastRuntime
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * 服务号官方触达端点（/api/admin 鉴权域内）：欢迎语模板维护与全员广播。
 * 广播创建后异步执行，进度按台账轮询；重复执行对已送达用户幂等。
 */
internal fun Route.adminServiceAccountRoutes(
    service: ServiceAccountMessages,
    runtime: ServiceBroadcastRuntime,
) {
    route("/service") {
        get("/welcome") {
            call.respond(mapOf("template" to service.getWelcomeTemplate()))
        }
        put("/welcome") {
            val req = call.receiveBoundedJsonOrRespond<WelcomeTemplateRequest>() ?: return@put
            val template = try {
                service.setWelcomeTemplate(req.content, call.requireAdminPrincipal())
            } catch (rejected: IllegalArgumentException) {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to rejected.message))
            }
            call.respond(mapOf("template" to template))
        }

        get("/broadcasts") {
            call.respond(mapOf("broadcasts" to service.listBroadcasts()))
        }
        post("/broadcasts") {
            val req = call.receiveBoundedJsonOrRespond<CreateBroadcastRequest>() ?: return@post
            val record = try {
                service.createBroadcast(req.markdown, call.requireAdminPrincipal(), runtime::launch)
            } catch (rejected: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to rejected.message))
            }
            call.respond(record)
        }
        get("/broadcasts/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!ServiceAccountMessages.isValidBroadcastId(id)) {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid broadcast id"))
            }
            val record = service.getBroadcast(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "broadcast not found"))
            call.respond(record)
        }
        post("/broadcasts/{id}/reissue") {
            val id = call.parameters["id"].orEmpty()
            if (!ServiceAccountMessages.isValidBroadcastId(id)) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid broadcast id"))
            }
            if (runtime.isRunning(id)) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "broadcast is still running"),
                )
            }
            val record = try {
                service.reissueBroadcast(id, runtime::launch)
            } catch (missing: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to missing.message))
            }
            call.respond(record)
        }
    }
}

@Serializable
data class WelcomeTemplateRequest(val content: String)

@Serializable
data class CreateBroadcastRequest(val markdown: String)
