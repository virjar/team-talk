package com.virjar.tk.api

import com.virjar.tk.domain.bot.BotAuthenticationException
import com.virjar.tk.domain.bot.BotAuthorizationException
import com.virjar.tk.domain.bot.BotRateLimitException
import com.virjar.tk.domain.bot.BotRequestException
import com.virjar.tk.domain.bot.BotService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class BotMessageRequest(
    val chatId: String,
    val markdown: String,
    val idempotencyKey: String,
)

/** 外部系统通知入口。token 只代表一个 bot，chatId 还必须通过显式授权白名单。 */
fun Route.botRoutes(service: BotService) {
    route("/api/v1/bots") {
        post("/{botId}/messages") {
            val botId = call.parameters["botId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "botId required"))
            val token = call.request.headers[HttpHeaders.Authorization]
                ?.removePrefix("Bearer ")
                ?.takeIf(String::isNotBlank)
            val request = call.receive<BotMessageRequest>()
            try {
                call.respond(service.deliver(botId, token, request.chatId, request.markdown, request.idempotencyKey))
            } catch (_: BotAuthenticationException) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid bot credentials"))
            } catch (e: BotAuthorizationException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to (e.message ?: "request rejected")))
            } catch (e: BotRateLimitException) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to (e.message ?: "rate limited")))
            } catch (e: BotRequestException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid request")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid request")))
            }
        }
    }
}
