package com.virjar.tk.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.virjar.tk.dto.ApiError
import com.virjar.tk.dto.AuthResponse
import com.virjar.tk.dto.LoginRequest
import com.virjar.tk.dto.RegisterRequest
import com.virjar.tk.storage.TokenStorage
import com.virjar.tk.util.AppLog

class ApiClient(
    config: ServerConfig = ServerConfig(),
    private val tokenStorage: TokenStorage = TokenStorage(),
) {
    var onUnauthorized: (() -> Unit)? = null
    var baseUrl: String = config.baseUrl
        private set
    var tcpHost: String = config.tcpHost
        private set
    var tcpPort: Int = config.tcpPort
        private set

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    /** 检查响应状态，非 2xx 时抛出 ApiException，否则反序列化为 T */
    private suspend inline fun <reified T> handleResponse(response: HttpResponse): T {
        if (response.status.isSuccess()) {
            return response.body<T>()
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            onUnauthorized?.invoke()
        }
        val bodyText = try { response.body<String>() } catch (_: Exception) { "" }
        val err = try { json.decodeFromString<ApiError>(bodyText) } catch (_: Exception) { null }
        throw ApiException(
            code = err?.code ?: 0,
            httpStatus = response.status.value,
            message = err?.message ?: "请求失败 (${response.status.value})",
        )
    }

    fun getTokenStorage(): TokenStorage = tokenStorage

    /**
     * Restore session from persistent storage.
     * Returns (token, uid, userJson) if a session was found, null otherwise.
     */
    fun restoreSession(): Triple<String, String, String>? {
        val token = tokenStorage.loadToken() ?: return null
        val uid = tokenStorage.loadUid() ?: return null
        val userJson = tokenStorage.loadUserJson() ?: return null
        AppLog.i("ApiClient", "Session found: uid=$uid")
        return Triple(token, uid, userJson)
    }

    suspend fun register(username: String, password: String, name: String): AuthResponse {
        return try {
            val resp = client.post("$baseUrl/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(username = username, password = password, name = name))
            }
            val result = handleResponse<AuthResponse>(resp)
            AppLog.i("ApiClient", "Register OK: uid=${result.uid}")
            result
        } catch (e: ApiException) {
            AppLog.e("ApiClient", "Register failed: $username — ${e.message}")
            throw e
        } catch (e: Exception) {
            AppLog.e("ApiClient", "Register failed: $username", e)
            throw e
        }
    }

    suspend fun login(username: String, password: String): AuthResponse {
        return try {
            val resp = client.post("$baseUrl/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username = username, password = password))
            }
            val result = handleResponse<AuthResponse>(resp)
            AppLog.i("ApiClient", "Login OK: uid=${result.uid}")
            result
        } catch (e: ApiException) {
            AppLog.e("ApiClient", "Login failed: $username — ${e.message}")
            throw e
        } catch (e: Exception) {
            AppLog.e("ApiClient", "Login failed: $username", e)
            throw e
        }
    }

    fun close() {
        client.close()
    }
}
