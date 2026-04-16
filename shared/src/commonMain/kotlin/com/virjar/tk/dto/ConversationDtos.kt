package com.virjar.tk.dto

import kotlinx.serialization.Serializable

@Serializable
data class ConversationDto(
    val channelId: String,
    val channelType: Int,
    val lastMsgSeq: Long = 0,
    val unreadCount: Int = 0,
    val readSeq: Long = 0,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val draft: String = "",
    val version: Long = 0,
    val channelName: String = "",
    val lastMessage: String = "",
    val lastMessageType: Int = 0,
    val lastMsgTimestamp: Long = 0,
)
