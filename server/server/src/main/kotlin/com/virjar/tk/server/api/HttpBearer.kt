package com.virjar.tk.server.api

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

private const val BEARER_PREFIX = "Bearer "

/**
 * 解析 Authorization: Bearer 头中的访问令牌。
 * 头缺失、非 Bearer 形式或令牌为空白时返回 null，由调用方决定 401 语义。
 */
internal fun ApplicationCall.bearerAuthorizationToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX) }
        ?.removePrefix(BEARER_PREFIX)
        ?.trim()
        ?.takeIf(String::isNotBlank)
