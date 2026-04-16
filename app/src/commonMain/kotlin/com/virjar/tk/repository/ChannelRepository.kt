package com.virjar.tk.repository

import com.virjar.tk.client.UserContext
import com.virjar.tk.database.LocalCache
import com.virjar.tk.dto.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class ChannelRepository(private val ctx: UserContext) {

    private val localCache: LocalCache get() = ctx.localCache

    /** Ensure a personal channel exists between current user and peer, returns the channel. */
    suspend fun ensurePersonalChannel(peerUid: String): ChannelDto {
        return ctx.httpClient.post("${ctx.baseUrl}/api/v1/channels/personal") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("uid" to peerUid))
        }.body<ChannelDto>()
    }

    /**
     * 先网络后缓存策略：HTTP 获取后写入 localCache。
     */
    suspend fun syncChannels(version: Long = 0): List<ChannelDto> {
        val channels = ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/sync?version=$version") {
            header("Authorization", ctx.authHeader())
        }.body<List<ChannelDto>>()
        withContext(Dispatchers.IO) { localCache.insertChannels(channels) }
        return channels
    }

    /**
     * 先查 localCache，miss 时走 HTTP。
     */
    suspend fun getChannel(channelId: String): ChannelDto {
        val cached = withContext(Dispatchers.IO) { localCache.getChannel(channelId) }
        if (cached != null) return cached
        val channel = ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/$channelId") {
            header("Authorization", ctx.authHeader())
        }.body<ChannelDto>()
        withContext(Dispatchers.IO) { localCache.insertChannel(channel) }
        return channel
    }

    suspend fun createGroup(name: String, memberUids: List<String>): ChannelDto {
        return ctx.httpClient.post("${ctx.baseUrl}/api/v1/channels") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(CreateGroupRequest(name = name, members = memberUids))
        }.body<ChannelDto>()
    }

    suspend fun getMembers(channelId: String, page: Int = 1): List<ChannelMemberDto> {
        return ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/$channelId/members?page=$page") {
            header("Authorization", ctx.authHeader())
        }.body<List<ChannelMemberDto>>()
    }

    suspend fun addMembers(channelId: String, uids: List<String>) {
        ctx.httpClient.post("${ctx.baseUrl}/api/v1/channels/$channelId/members") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("uids" to uids))
        }
    }

    suspend fun removeMembers(channelId: String, uids: List<String>) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/channels/$channelId/members") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("uids" to uids))
        }
    }

    suspend fun deleteChannel(channelId: String) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/channels/$channelId") {
            header("Authorization", ctx.authHeader())
        }
    }

    suspend fun updateChannel(channelId: String, name: String? = null, notice: String? = null): ChannelDto {
        return ctx.httpClient.put("${ctx.baseUrl}/api/v1/channels/$channelId") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            val body = mutableMapOf<String, String>()
            if (name != null) body["name"] = name
            if (notice != null) body["notice"] = notice
            setBody(body)
        }.body<ChannelDto>()
    }

    /** Upload group avatar via multipart POST. Returns the updated channel. */
    suspend fun uploadGroupAvatar(groupNo: String, bytes: ByteArray, fileName: String = "avatar.jpg"): ChannelDto {
        return ctx.httpClient.post("${ctx.baseUrl}/api/v1/groups/$groupNo/avatar") {
            header("Authorization", ctx.authHeader())
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, "image/jpeg")
                    })
                }
            ))
        }.body<ChannelDto>()
    }

    /** Mute a group member. durationSeconds: 0=permanent */
    suspend fun muteMember(channelId: String, targetUid: String, durationSeconds: Long) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/channels/$channelId/members/$targetUid/mute") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("durationSeconds" to durationSeconds))
        }
    }

    /** Unmute a group member. */
    suspend fun unmuteMember(channelId: String, targetUid: String) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/channels/$channelId/members/$targetUid/mute") {
            header("Authorization", ctx.authHeader())
        }
    }

    /** Toggle mute-all for a group channel. */
    suspend fun setMutedAll(channelId: String, mutedAll: Boolean) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/channels/$channelId/mute-all") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("mutedAll" to mutedAll))
        }
    }

    /** Set a member's role (0=member, 1=admin, 2=owner). */
    suspend fun setMemberRole(channelId: String, targetUid: String, role: Int) {
        ctx.httpClient.put("${ctx.baseUrl}/api/v1/channels/$channelId/members/$targetUid/role") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf("role" to role))
        }
    }

    // ── 邀请链接 ──

    /** Create an invite link for a group channel. expiresIn in milliseconds. */
    suspend fun createInviteLink(
        channelId: String,
        name: String? = null,
        maxUses: Int? = null,
        expiresIn: Long? = null,
    ): InviteLinkDto {
        return ctx.httpClient.post("${ctx.baseUrl}/api/v1/channels/$channelId/invite-links") {
            header("Authorization", ctx.authHeader())
            contentType(ContentType.Application.Json)
            setBody(mapOf<String, Any?>(
                "name" to name,
                "maxUses" to maxUses,
                "expiresIn" to expiresIn,
            ))
        }.body<InviteLinkDto>()
    }

    /** List active invite links for a group channel. */
    suspend fun getInviteLinks(channelId: String): List<InviteLinkDto> {
        return ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/$channelId/invite-links") {
            header("Authorization", ctx.authHeader())
        }.body<List<InviteLinkDto>>()
    }

    /** Revoke an invite link. */
    suspend fun revokeInviteLink(channelId: String, token: String) {
        ctx.httpClient.delete("${ctx.baseUrl}/api/v1/channels/$channelId/invite-links/$token") {
            header("Authorization", ctx.authHeader())
        }
    }

    /** Join a group via invite link. */
    suspend fun joinByInviteLink(token: String): JoinByLinkResultDto {
        return ctx.httpClient.post("${ctx.baseUrl}/api/v1/channels/invite/$token/join") {
            header("Authorization", ctx.authHeader())
        }.body<JoinByLinkResultDto>()
    }

    /** Get invite link preview info. */
    suspend fun getInviteLinkInfo(token: String): InviteLinkInfoDto {
        return ctx.httpClient.get("${ctx.baseUrl}/api/v1/channels/invite/$token/info") {
            header("Authorization", ctx.authHeader())
        }.body<InviteLinkInfoDto>()
    }
}
