package com.virjar.tk.api

import com.virjar.tk.dto.ApiError
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*

class BusinessException(
    val errorCode: Int,
    override val message: String,
    val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest,
) : Exception(message)

fun ApplicationCall.requireUid(): String {
    return principal<JWTPrincipal>()?.payload?.subject
        ?: throw BusinessException(401, "Unauthorized", HttpStatusCode.Unauthorized)
}
