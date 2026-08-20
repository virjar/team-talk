package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.http.CreateGroupBotRequest
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import com.virjar.tk.outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface GroupBotManagementRepository : AutoCloseable {
    suspend fun list(chatId: String): Outcome<List<GroupBotSummary>>
    suspend fun create(chatId: String, name: String): Outcome<GroupBotCredentials>
    suspend fun rotate(chatId: String, botId: String): Outcome<GroupBotCredentials>
    suspend fun remove(chatId: String, botId: String): Outcome<Unit>
}

/**
 * Session-owned HTTP adapter for group bot administration.
 *
 * The server and [ownerUid] are immutable. A fresh atomic uid/token snapshot is obtained for every
 * request so reconnect token rotation is visible without ever allowing a retired repository to
 * borrow the next account's credentials. [close] invalidates the publication gate first and then
 * disconnects every active platform request.
 */
class HttpGroupBotManagementRepository internal constructor(
    serverUrl: String,
    ownerUid: String,
    credentialsProvider: () -> SessionHttpCredentials,
    private val transport: PlatformGroupBotHttpTransport,
) : GroupBotManagementRepository {
    constructor(
        serverUrl: String,
        ownerUid: String,
        credentialsProvider: () -> SessionHttpCredentials,
    ) : this(serverUrl, ownerUid, credentialsProvider, createPlatformGroupBotHttpTransport())

    private val baseUrl = canonicalHttpServerBase(serverUrl)
    private val credentialGate = GroupBotCredentialGate(ownerUid, credentialsProvider)

    override suspend fun list(chatId: String): Outcome<List<GroupBotSummary>> = request(
        method = "GET",
        path = GroupBotHttpContract.listPath(chatId),
        decode = GroupBotHttpContract::decodeList,
    )

    override suspend fun create(chatId: String, name: String): Outcome<GroupBotCredentials> = request(
        method = "POST",
        path = GroupBotHttpContract.listPath(chatId),
        jsonBody = GroupBotHttpContract.encodeCreate(name),
        decode = GroupBotHttpContract::decodeCredentials,
    )

    override suspend fun rotate(chatId: String, botId: String): Outcome<GroupBotCredentials> = request(
        method = "POST",
        path = GroupBotHttpContract.rotatePath(chatId, botId),
        decode = GroupBotHttpContract::decodeCredentials,
    )

    override suspend fun remove(chatId: String, botId: String): Outcome<Unit> = request(
        method = "DELETE",
        path = GroupBotHttpContract.botPath(chatId, botId),
        decode = {},
    )

    private suspend fun <T> request(
        method: String,
        path: String,
        jsonBody: String? = null,
        decode: (String) -> T,
    ): Outcome<T> = outcome {
        val credentials = credentialGate.requireCredentials()
        val body = transport.request(
            method = method,
            url = baseUrl + path,
            bearerToken = credentials.accessToken,
            jsonBody = jsonBody,
        )
        credentialGate.publishResponse { decode(body) }
    }

    override fun close() {
        if (!credentialGate.close()) return
        transport.close()
    }
}

internal data class RequiredGroupBotCredentials(val accessToken: String)

internal class GroupBotCredentialGate(
    private val ownerUid: String,
    private val credentialsProvider: () -> SessionHttpCredentials,
) {
    private val lock = Any()
    private var closed = false
    private val ownerIdentityEpoch: Long

    init {
        require(ownerUid.isNotBlank()) { "群机器人仓库 owner uid 不能为空" }
        val initial = credentialsProvider()
        check(initial.uid == ownerUid) { "群机器人仓库初始认证身份不匹配" }
        ownerIdentityEpoch = initial.identityEpoch
    }

    fun requireCredentials(): RequiredGroupBotCredentials = synchronized(lock) {
        check(!closed) { "群机器人仓库已经关闭" }
        val snapshot = credentialsProvider()
        requireCurrentOwner(snapshot)
        val token = snapshot.accessToken?.takeIf(String::isNotBlank)
            ?: error("认证会话缺少群机器人访问凭据")
        require(token.all { it.code in 0x21..0x7e }) { "群机器人访问凭据包含非法字符" }
        RequiredGroupBotCredentials(token)
    }

    /** Linearizes response publication against close and an account identity change. */
    fun <T> publishResponse(block: () -> T): T = synchronized(lock) {
        check(!closed) { "群机器人仓库已经关闭" }
        requireCurrentOwner(credentialsProvider())
        block()
    }

    fun close(): Boolean = synchronized(lock) {
        if (closed) false else {
            closed = true
            true
        }
    }

    private fun requireCurrentOwner(snapshot: SessionHttpCredentials) {
        check(snapshot.uid == ownerUid && snapshot.identityEpoch == ownerIdentityEpoch) {
            "群机器人仓库所属认证会话已经变更"
        }
    }
}

internal interface PlatformGroupBotHttpTransport {
    suspend fun request(
        method: String,
        url: String,
        bearerToken: String,
        jsonBody: String? = null,
    ): String

    fun close()
}

internal expect fun createPlatformGroupBotHttpTransport(): PlatformGroupBotHttpTransport

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
