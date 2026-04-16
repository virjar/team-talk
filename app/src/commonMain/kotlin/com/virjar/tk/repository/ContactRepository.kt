package com.virjar.tk.repository

import com.virjar.tk.client.UserContext
import com.virjar.tk.database.LocalCache
import com.virjar.tk.dto.*
import com.virjar.tk.util.AppLog
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class ContactRepository(private val ctx: UserContext) {

    private val localCache: LocalCache get() = ctx.localCache

    /**
     * 先网络后缓存策略：HTTP 获取后写入 localCache，返回服务端结果。
     * 网络失败时回退到本地缓存。
     */
    suspend fun getFriends(): List<FriendDto> {
        return try {
            val friends = ctx.httpClient.get("${ctx.baseUrl}/api/v1/contacts") {
                header("Authorization", ctx.authHeader())
            }.body<List<FriendDto>>()
            withContext(Dispatchers.IO) { localCache.insertContacts(friends) }
            friends
        } catch (e: Exception) {
            AppLog.e("ContactRepo", "getFriends failed", e)
            val local = withContext(Dispatchers.IO) { localCache.getAllContacts() }
            if (local.isNotEmpty()) local else throw e
        }
    }

    suspend fun searchUsers(query: String): List<UserDto> {
        return try {
            ctx.httpClient.get("${ctx.baseUrl}/api/v1/users/search?q=$query") {
                header("Authorization", ctx.authHeader())
            }.body()
        } catch (e: Exception) {
            AppLog.e("ContactRepo", "searchUsers failed: q=$query", e)
            throw e
        }
    }

    suspend fun applyFriend(toUid: String, remark: String = "") {
        ctx.httpClient.post("${ctx.baseUrl}/api/v1/contacts/apply") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("toUid" to toUid, "remark" to remark))
        }
    }

    suspend fun acceptApply(token: String) {
        ctx.httpClient.post("${ctx.baseUrl}/api/v1/contacts/accept") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("token" to token))
        }
    }

    suspend fun getApplies(page: Int = 1): List<FriendApplyDto> {
        return ctx.httpClient.get("${ctx.baseUrl}/api/v1/contacts/applies?page=$page") {
            header("Authorization", ctx.authHeader())
        }.body<List<FriendApplyDto>>()
    }

    suspend fun deleteFriend(friendUid: String) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/contacts/$friendUid") {
            header("Authorization", ctx.authHeader())
        }
        withContext(Dispatchers.IO) { localCache.deleteContact(friendUid) }
    }

    suspend fun updateRemark(friendUid: String, remark: String) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/contacts/$friendUid/remark") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("remark" to remark))
        }
    }

    // ── Blacklist ──

    suspend fun getBlacklist(): List<BlacklistDto> {
        return ctx.httpClient.get("${ctx.baseUrl}/api/v1/blacklist") {
            header("Authorization", ctx.authHeader())
        }.body<List<BlacklistDto>>()
    }

    suspend fun addBlacklist(uid: String) {
        ctx.httpClient.post("${ctx.baseUrl}/api/v1/blacklist/$uid") {
            header("Authorization", ctx.authHeader())
        }
    }

    suspend fun removeBlacklist(uid: String) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/blacklist/$uid") {
            header("Authorization", ctx.authHeader())
        }
    }
}
