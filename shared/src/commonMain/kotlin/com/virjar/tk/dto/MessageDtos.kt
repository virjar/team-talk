package com.virjar.tk.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncMessagesRequest(
    val lastSeq: Long = 0,
    val limit: Int = 50,
    val pullMode: Int = 0,
)

@Serializable
data class EditMessageRequest(
    val newText: String,
)

@Serializable
data class MessageSearchResponse(
    val total: Int,
    val results: List<MessageSearchResult>,
)

@Serializable
data class MessageSearchResult(
    val messageId: String,
    val channelId: String,
    val channelType: Int,
    val channelName: String = "",
    val senderUid: String,
    val senderName: String = "",
    val messageType: Int,
    val seq: Long,
    val timestamp: Long,
    val highlight: String,
)
