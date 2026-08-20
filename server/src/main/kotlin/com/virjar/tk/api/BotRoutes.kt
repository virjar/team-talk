package com.virjar.tk.api

import com.virjar.tk.domain.bot.BotAuthenticationException
import com.virjar.tk.domain.bot.BotAuthorizationException
import com.virjar.tk.domain.bot.BotMessageDelivery
import com.virjar.tk.domain.bot.BotRateLimitException
import com.virjar.tk.domain.bot.BotRequestException
import com.virjar.tk.domain.bot.BotService
import com.virjar.tk.domain.bot.GroupBotManagement
import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.http.BOT_IDEMPOTENCY_KEY_HEADER
import com.virjar.tk.http.CreateGroupBotRequest
import com.virjar.tk.http.GroupBotMessageRequest
import com.virjar.tk.http.GroupBotMessageResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/** External bot delivery plus access-token-authenticated group management. */
fun Route.botRoutes(service: BotService, accessTokens: AccessTokenValidator) {
    targetBoundBotMessageRoutes(service)
    groupBotRoutes(service, accessTokens)
}

/**
 * Canonical target-bound webhook. The URL chatId is the sole delivery destination.
 */
internal fun Route.targetBoundBotMessageRoutes(
    service: BotMessageDelivery,
    newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) {
    post("/api/v1/groups/{chatId}/bots/{botId}/messages") {
        val chatId = call.parameters["chatId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "chatId required"))
        val botId = call.parameters["botId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "botId required"))
        try {
            val request = call.receive<GroupBotMessageRequest>()
            val idempotencyKey = call.request.headers[BOT_IDEMPOTENCY_KEY_HEADER] ?: newIdempotencyKey()
            service.deliver(
                botId = botId,
                token = call.botBearerToken(),
                chatId = chatId,
                markdown = request.markdown,
                idempotencyKey = idempotencyKey,
            )
            call.respond(GroupBotMessageResponse(ok = true))
        } catch (error: IllegalArgumentException) {
            call.respondBotDeliveryError(error)
        }
    }
}

private fun ApplicationCall.botBearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.takeIf(String::isNotBlank)

private suspend fun ApplicationCall.respondBotDeliveryError(error: IllegalArgumentException) {
    when (error) {
        is BotAuthenticationException ->
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid bot credentials"))
        is BotAuthorizationException ->
            respond(HttpStatusCode.Forbidden, mapOf("error" to (error.message ?: "request rejected")))
        is BotRateLimitException ->
            respond(HttpStatusCode.TooManyRequests, mapOf("error" to (error.message ?: "rate limited")))
        is BotRequestException ->
            respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid request")))
        else -> respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid request")))
    }
}

internal fun Route.groupBotRoutes(service: GroupBotManagement, accessTokens: AccessTokenValidator) {
    route("/api/v1/groups/{chatId}/bots") {
        get {
            val actorUid = call.groupBotActorUid(accessTokens) ?: return@get
            val chatId = call.parameters["chatId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "chatId required"))
            try {
                call.respond(service.listForGroup(actorUid, chatId))
            } catch (e: BotAuthorizationException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to (e.message ?: "request rejected")))
            }
        }
        post {
            val actorUid = call.groupBotActorUid(accessTokens) ?: return@post
            val chatId = call.parameters["chatId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "chatId required"))
            try {
                call.respond(service.createForGroup(actorUid, chatId, call.receive<CreateGroupBotRequest>().name))
            } catch (e: BotAuthorizationException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to (e.message ?: "request rejected")))
            } catch (e: BotRateLimitException) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to (e.message ?: "rate limited")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid request")))
            }
        }
        post("/{botId}/rotate-token") {
            val actorUid = call.groupBotActorUid(accessTokens) ?: return@post
            val chatId = call.parameters["chatId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "chatId required"))
            val botId = call.parameters["botId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "botId required"))
            try {
                call.respond(service.rotateTokenForGroup(actorUid, chatId, botId))
            } catch (e: BotAuthorizationException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to (e.message ?: "request rejected")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid request")))
            }
        }
        delete("/{botId}") {
            val actorUid = call.groupBotActorUid(accessTokens) ?: return@delete
            val chatId = call.parameters["chatId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "chatId required"))
            val botId = call.parameters["botId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "botId required"))
            try {
                service.removeFromGroup(actorUid, chatId, botId)
                call.respond(HttpStatusCode.NoContent)
            } catch (e: BotAuthorizationException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to (e.message ?: "request rejected")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid request")))
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.groupBotActorUid(
    accessTokens: AccessTokenValidator,
): String? {
    val token = request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.takeIf(String::isNotBlank)
    val uid = token?.let { accessTokens.validateAccessToken(it) }?.uid
    if (uid == null) respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
    return uid
}
