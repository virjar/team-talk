package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.http.CreateGroupBotRequest
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface GroupBotManagementRepository {
    suspend fun list(chatId: String): Outcome<List<GroupBotSummary>>
    suspend fun create(chatId: String, name: String): Outcome<GroupBotCredentials>
    suspend fun rotate(chatId: String, botId: String): Outcome<GroupBotCredentials>
    suspend fun remove(chatId: String, botId: String): Outcome<Unit>
}

expect class HttpGroupBotManagementRepository(
    serverUrl: String,
    accessToken: String?,
) : GroupBotManagementRepository

/** HTTP wire details shared by Android and Desktop; credentials are never logged or persisted. */
internal object GroupBotHttpContract {
    const val MAX_RESPONSE_BYTES = 1_048_576
    private val json = Json { ignoreUnknownKeys = true }

    fun listPath(chatId: String): String = "/api/v1/groups/${safeId(chatId, "chatId")}/bots"

    fun botPath(chatId: String, botId: String): String =
        listPath(chatId) + "/${safeId(botId, "botId")}"

    fun rotatePath(chatId: String, botId: String): String = botPath(chatId, botId) + "/rotate-token"

    fun encodeCreate(name: String): String = json.encodeToString(CreateGroupBotRequest.serializer(), CreateGroupBotRequest(name))

    fun decodeList(body: String): List<GroupBotSummary> =
        json.decodeFromString(ListSerializer(GroupBotSummary.serializer()), body)

    fun decodeCredentials(body: String): GroupBotCredentials =
        json.decodeFromString(GroupBotCredentials.serializer(), body)

    fun errorMessage(body: String, fallback: String): String =
        runCatching { json.decodeFromString(GroupBotHttpError.serializer(), body).error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    private fun safeId(value: String, field: String): String {
        require(value.length in 1..64) { "$field 非法" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "$field 非法" }
        return value
    }
}

@Serializable
private data class GroupBotHttpError(val error: String)
