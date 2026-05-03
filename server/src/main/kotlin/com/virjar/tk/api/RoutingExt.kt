package com.virjar.tk.api

import io.ktor.server.auth.*
import io.ktor.server.routing.*

fun Route.requireAuth(build: Route.() -> Unit) {
    authenticate("auth-jwt", build = build)
}
