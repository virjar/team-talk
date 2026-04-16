package com.virjar.tk.api

import com.virjar.tk.dto.ApiError
import io.ktor.http.*

class BusinessException(
    val errorCode: Int,
    override val message: String,
    val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest,
) : Exception(message)
