package com.virjar.tk.api

import com.virjar.tk.dto.*
import com.virjar.tk.service.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.authRoutes(userService: UserService, tokenService: TokenService) {
    route("/api/v1/auth") {
        post("/register") {
            val req = call.receive<RegisterRequest>()
            val resp = userService.register(req)
            call.respond(HttpStatusCode.Created, resp)
        }

        post("/login") {
            val req = call.receive<LoginRequest>()
            val resp = userService.login(req)
            call.respond(resp)
        }

        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            val resp = userService.refresh(req.refreshToken)
            call.respond(resp)
        }

        requireAuth {
            delete("/logout") {
                val uid = call.requireUid()
                val body = runCatching { call.receive<RefreshRequest>() }.getOrNull()
                userService.logout(uid, body?.refreshToken)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/api/v1/users") {
        requireAuth {
            get("/me") {
                val uid = call.requireUid()
                val user = userService.getUser(uid)
                call.respond(user)
            }

            put("/me") {
                val uid = call.requireUid()
                val req = call.receive<UpdateProfileRequest>()
                val user = userService.updateUser(uid, req.name, req.avatar, req.sex)
                call.respond(user)
            }

            get("/{uid}") {
                val targetUid = call.parameters["uid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val user = userService.getUser(targetUid)
                call.respond(user)
            }

            get("/search") {
                val q = call.request.queryParameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val results = userService.searchUsers(q)
                call.respond(results)
            }
        }
    }
}
