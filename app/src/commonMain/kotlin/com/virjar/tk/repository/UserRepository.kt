package com.virjar.tk.repository

import com.virjar.tk.client.UserContext
import com.virjar.tk.dto.UserDto
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UserRepository(private val ctx: UserContext) {

    suspend fun getMe(): UserDto {
        return try {
            ctx.httpClient.get("${ctx.baseUrl}/api/v1/users/me") {
                header("Authorization", ctx.authHeader())
            }.body()
        } catch (e: Exception) {
            com.virjar.tk.util.AppLog.e("UserRepo", "getMe failed", e)
            throw e
        }
    }

    suspend fun getUser(uid: String): UserDto {
        val response = ctx.httpClient.get("${ctx.baseUrl}/api/v1/users/$uid") {
            header("Authorization", ctx.authHeader())
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("getUser failed: HTTP ${response.status.value} for uid=$uid")
        }
        return response.body<UserDto>()
    }

    suspend fun updateProfile(name: String? = null, avatar: String? = null, sex: Int? = null): UserDto {
        val body = buildJsonObject {
            name?.let { put("name", it) }
            avatar?.let { put("avatar", it) }
            sex?.let { put("sex", it) }
        }
        return ctx.httpClient.put("${ctx.baseUrl}/api/v1/users/me") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<UserDto>()
    }

    suspend fun getOnlineStatus(uids: List<String>): Map<String, Boolean> {
        if (uids.isEmpty()) return emptyMap()
        return try {
            val response: Map<String, Map<String, Boolean>> = ctx.httpClient.post("${ctx.baseUrl}/api/v1/users/online-status") {
                header("Authorization", ctx.authHeader())
                contentType(ContentType.Application.Json)
                setBody(mapOf("uids" to uids))
            }.body()
            response["status"] ?: emptyMap()
        } catch (e: Exception) {
            com.virjar.tk.util.AppLog.e("UserRepo", "getOnlineStatus failed", e)
            throw e
        }
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/user/updatepassword") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("password" to oldPassword, "new_password" to newPassword))
        }
    }
}
