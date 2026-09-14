package com.virjar.tk.server.api

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 公开说明页不读取邀请资料；URL fragment 只留在浏览器，实际预览和加入由客户端 RPC 完成。 */
internal fun Route.inviteLandingRoutes() {
    get("/invite") {
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.respondText(inviteLandingPage, ContentType.Text.Html)
    }
}

private val inviteLandingPage: String by lazy {
    checkNotNull(InviteLandingPageResource::class.java.getResourceAsStream("/invite.html")) {
        "Missing invitation landing page"
    }.bufferedReader().use { it.readText() }
}

private object InviteLandingPageResource
