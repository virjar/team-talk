package com.virjar.tk.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChannelDto(
    val channelId: String,
    val channelType: Int,
    val name: String,
    val avatar: String = "",
    val creator: String? = null,
    val maxSeq: Long = 0,
    val memberCount: Int = 0,
    val notice: String = "",
    val mutedAll: Boolean = false,
)

@Serializable
data class ChannelMemberDto(
    val uid: String,
    val channelId: String = "",
    val role: Int = 0,
    val nickname: String = "",
    val status: Int = 1,
    val joinedAt: Long = 0,
    val user: UserDto? = null,
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val avatar: String = "",
    val members: List<String>,
)

@Serializable
data class UpdateChannelRequest(
    val name: String? = null,
    val avatar: String? = null,
    val notice: String? = null,
)

@Serializable
data class CreateInviteLinkRequest(
    val name: String? = null,
    val maxUses: Int? = null,
    val expiresIn: Long? = null,
)

@Serializable
data class InviteLinkDto(
    val token: String,
    val channelId: String,
    val creatorUid: String,
    val name: String? = null,
    val maxUses: Int? = null,
    val useCount: Int = 0,
    val expiresAt: Long? = null,
    val createdAt: Long,
    val url: String = "",
)

@Serializable
data class InviteLinkInfoDto(
    val channelId: String,
    val channelName: String,
    val memberCount: Int,
    val expiresAt: Long? = null,
)

@Serializable
data class JoinByLinkResultDto(
    val joined: Boolean,
    val channelId: String,
    val channelName: String,
)
