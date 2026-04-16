package com.virjar.tk.dto

import kotlinx.serialization.Serializable

@Serializable
data class FriendDto(
    val uid: String,
    val friendUid: String,
    val friendName: String = "",
    val friendUsername: String = "",
    val remark: String = "",
    val status: Int = 1,
    val version: Long = 0,
)

@Serializable
data class FriendApplyDto(
    val fromUid: String,
    val fromName: String = "",
    val toUid: String,
    val token: String,
    val remark: String = "",
    val status: Int = 0,
    val createdAt: Long = 0,
)

@Serializable
data class BlacklistDto(
    val uid: String,
    val name: String = "",
    val username: String = "",
)

@Serializable
data class ApplyFriendRequest(
    val toUid: String,
    val remark: String = "",
)
