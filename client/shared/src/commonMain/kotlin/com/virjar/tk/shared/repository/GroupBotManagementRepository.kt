package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.GroupBotCredentialCommandKind
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingGroupBotCredentialCommand
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.protocol.http.CreateGroupBotRequest
import com.virjar.tk.protocol.http.GROUP_BOT_NAME_MAX_LENGTH
import com.virjar.tk.protocol.http.GroupBotCommandReceipt
import com.virjar.tk.protocol.http.GroupBotCredentials
import com.virjar.tk.protocol.http.GroupBotSummary
import com.virjar.tk.protocol.http.RotateGroupBotTokenRequest
import com.virjar.tk.shared.outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class RecoveredGroupBotCredentials(
    val chatId: String,
    val credentials: GroupBotCredentials,
)

/** 客户端所持命令的脱敏身份；可恢复的 token 绝不会进入 UI 状态。 */
data class PendingGroupBotCredentialRecovery(
    val operationId: String,
    val chatId: String,
    val kind: GroupBotCredentialCommandKind,
    val botId: String?,
    val name: String?,
)

/**
 * 由会话持有的群机器人管理 HTTP 适配器。
 *
 * 服务器与 [ownerUid] 不可变。每次请求都获取一份新的原子 uid/token 快照，
 * 因此重连的 token 轮换是可见的，同时已退场的 repository 也永远无法借用
 * 下一个账号的凭据。[close] 先使发布门禁失效，然后
 * 断开每个活跃的平台请求。
 */
class GroupBotManagementRepository(
    serverUrl: String,
    private val ownerUid: String,
    credentialsProvider: () -> SessionHttpCredentials,
    private val localCache: LocalCache,
    private val onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
) : AutoCloseable {
    private val baseUrl = canonicalSecureGroupBotServerBase(serverUrl)
    private val transport = createPlatformGroupBotHttpTransport()
    private val credentialGate = GroupBotCredentialGate(ownerUid, credentialsProvider)
    private val credentialCommandMutex = Mutex()

    suspend fun list(chatId: String): Outcome<List<GroupBotSummary>> = request(
        method = "GET",
        path = GroupBotHttpContract.listPath(chatId),
        decode = GroupBotHttpContract::decodeList,
    )

    suspend fun create(chatId: String, name: String): Outcome<GroupBotCredentials> = outcome {
        credentialCommandMutex.withLock {
            val candidate = PendingGroupBotCredentialCommand.create(
                ownerUid = ownerUid,
                chatId = chatId,
                name = name,
            )
            val command = localCache.preparePendingGroupBotCredentialCommand(candidate)
            executeCredentialCommand(command)
        }
    }

    suspend fun rotate(chatId: String, botId: String): Outcome<GroupBotCredentials> = outcome {
        credentialCommandMutex.withLock {
            val candidate = PendingGroupBotCredentialCommand.rotate(
                ownerUid = ownerUid,
                chatId = chatId,
                botId = botId,
            )
            val command = localCache.preparePendingGroupBotCredentialCommand(candidate)
            executeCredentialCommand(command)
        }
    }

    suspend fun recoverPendingCredential(): Outcome<RecoveredGroupBotCredentials?> = outcome {
        credentialCommandMutex.withLock {
            val command = localCache.getPendingGroupBotCredentialCommand() ?: return@withLock null
            check(command.ownerUid == ownerUid) { "群机器人凭据命令所属账号不匹配" }
            RecoveredGroupBotCredentials(command.chatId, executeCredentialCommand(command))
        }
    }

    fun pendingCredentialRecovery(): PendingGroupBotCredentialRecovery? =
        credentialGate.publishLocalMutation {
            localCache.getPendingGroupBotCredentialCommand()?.toRecoveryIdentity()
        }

    fun acknowledgeCredential(operationId: String): Boolean =
        credentialGate.publishLocalMutation { localCache.clearPendingGroupBotCredentialCommand(operationId) }

    fun abandonPendingCredential(operationId: String): Boolean =
        credentialGate.publishLocalMutation { localCache.clearPendingGroupBotCredentialCommand(operationId) }

    suspend fun remove(chatId: String, botId: String): Outcome<Unit> = request(
        method = "DELETE",
        path = GroupBotHttpContract.botPath(chatId, botId),
        decode = {},
    )

    private suspend fun executeCredentialCommand(
        command: PendingGroupBotCredentialCommand,
    ): GroupBotCredentials {
        val receipt = try {
            when (command.kind) {
                GroupBotCredentialCommandKind.CREATE -> requestValue(
                    method = "POST",
                    path = GroupBotHttpContract.listPath(command.chatId),
                    jsonBody = GroupBotHttpContract.encodeCreate(command),
                    decode = GroupBotHttpContract::decodeReceipt,
                )
                GroupBotCredentialCommandKind.ROTATE -> requestValue(
                    method = "POST",
                    path = GroupBotHttpContract.rotatePath(command.chatId, requireNotNull(command.botId)),
                    jsonBody = GroupBotHttpContract.encodeRotate(command),
                    decode = GroupBotHttpContract::decodeReceipt,
                )
            }
        } catch (failure: AppError.Business) {
            if (failure.code in TERMINAL_CREDENTIAL_COMMAND_STATUSES) {
                // 在这些管理端点上，404 表示目标已不存在，410
                // 表示这份精确凭据已被取代或退场。409 被刻意设计为
                // 非终局：它可能表示操作身份/payload 复用的同时原始
                // token 仍然有效。其他失败同样保留唯一的可恢复本地副本。
                credentialGate.publishLocalMutation {
                    localCache.clearPendingGroupBotCredentialCommand(command.operationId)
                }
            }
            throw failure
        }
        GroupBotHttpContract.requireMatchingReceipt(command, receipt)
        return GroupBotCredentials(
            bot = receipt.bot,
            webhookToken = command.webhookToken,
            operationId = command.operationId,
        )
    }

    private suspend fun <T> request(
        method: String,
        path: String,
        jsonBody: String? = null,
        decode: (String) -> T,
    ): Outcome<T> = outcome { requestValue(method, path, jsonBody, decode) }

    private suspend fun <T> requestValue(
        method: String,
        path: String,
        jsonBody: String? = null,
        decode: (String) -> T,
    ): T {
        val credentials = credentialGate.requireCredentials()
        return try {
            val body = transport.request(
                method = method,
                url = baseUrl + path,
                bearerToken = credentials.accessToken,
                jsonBody = jsonBody,
            )
            credentialGate.publishResponse(credentials) { decode(body) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val authoritative = credentialGate.authoritativeFailure(credentials, failure)
            if (authoritative is AppError.AuthExpired) onAuthExpired(credentials.accessToken)
            throw authoritative
        }
    }

    override fun close() {
        credentialGate.close()
        transport.close()
    }
}

private fun PendingGroupBotCredentialCommand.toRecoveryIdentity() = PendingGroupBotCredentialRecovery(
    operationId = operationId,
    chatId = chatId,
    kind = kind,
    botId = botId,
    name = name,
)

/** 稳定路由契约：只有这些响应才能证明拟议的秘密不可能仍然可用。 */
private val TERMINAL_CREDENTIAL_COMMAND_STATUSES = setOf(404, 410)

internal data class RequiredGroupBotCredentials(val accessToken: String)

internal class SupersededGroupBotCredentialException :
    IllegalStateException("群机器人请求使用的认证凭据已经轮换")

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

    /** 将响应发布与 close 以及账号身份变更串行化。 */
    fun <T> publishResponse(credentials: RequiredGroupBotCredentials, block: () -> T): T = synchronized(lock) {
        check(!closed) { "群机器人仓库已经关闭" }
        requireCurrentCredentials(credentials)
        block()
    }

    /** 将任何持久凭据槽的读/改与 close 以及账号替换串行化。 */
    fun <T> publishLocalMutation(block: () -> T): T = synchronized(lock) {
        check(!closed) { "群机器人仓库已经关闭" }
        requireCurrentOwner(credentialsProvider())
        block()
    }

    /** 来自已被取代 bearer 的 401 并不能证明当前持久会话已过期。 */
    fun authoritativeFailure(
        credentials: RequiredGroupBotCredentials,
        failure: Exception,
    ): Exception = synchronized(lock) {
        check(!closed) { "群机器人仓库已经关闭" }
        try {
            requireCurrentCredentials(credentials)
            failure
        } catch (_: SupersededGroupBotCredentialException) {
            SupersededGroupBotCredentialException()
        }
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

    private fun requireCurrentCredentials(credentials: RequiredGroupBotCredentials) {
        val snapshot = credentialsProvider()
        requireCurrentOwner(snapshot)
        if (snapshot.accessToken != credentials.accessToken) {
            throw SupersededGroupBotCredentialException()
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

/** Bearer 凭据与新生成的 bot token 只能经由 TLS 传输，显式 loopback 除外。 */
internal expect fun canonicalSecureGroupBotServerBase(serverUrl: String): String

/** Android 与 Desktop 共用的 HTTP wire 细节；凭据绝不会被记录到日志。 */
internal object GroupBotHttpContract {
    const val MAX_RESPONSE_BYTES = 1_048_576
    private val json = Json { ignoreUnknownKeys = true }
    private val strictReceiptJson = Json { ignoreUnknownKeys = false }

    fun listPath(chatId: String): String = "/api/v1/groups/${safeId(chatId, "chatId")}/bots"

    fun botPath(chatId: String, botId: String): String =
        listPath(chatId) + "/${safeId(botId, "botId")}"

    fun rotatePath(chatId: String, botId: String): String = botPath(chatId, botId) + "/rotate-token"

    fun encodeCreate(command: PendingGroupBotCredentialCommand): String = json.encodeToString(
        CreateGroupBotRequest.serializer(),
        CreateGroupBotRequest(command.operationId, requireNotNull(command.name), command.webhookToken),
    )

    fun encodeRotate(command: PendingGroupBotCredentialCommand): String = json.encodeToString(
        RotateGroupBotTokenRequest.serializer(),
        RotateGroupBotTokenRequest(command.operationId, command.webhookToken),
    )

    fun decodeList(body: String): List<GroupBotSummary> =
        json.decodeFromString(ListSerializer(GroupBotSummary.serializer()), body)

    fun decodeReceipt(body: String): GroupBotCommandReceipt =
        strictReceiptJson.decodeFromString(GroupBotCommandReceipt.serializer(), body)

    fun requireMatchingReceipt(
        command: PendingGroupBotCredentialCommand,
        receipt: GroupBotCommandReceipt,
    ) {
        require(receipt.operationId == command.operationId) { "机器人凭据响应 operationId 不匹配" }
        safeId(receipt.bot.botId, "botId")
        command.botId?.let { expected ->
            require(receipt.bot.botId == expected) { "机器人凭据响应 botId 不匹配" }
        }
        require(receipt.bot.apiPath == messagePath(command.chatId, receipt.bot.botId)) {
            "机器人凭据响应目标群不匹配"
        }
        require(
            receipt.bot.status == 1 && receipt.bot.groupManaged && receipt.bot.createdByMe &&
                receipt.bot.canRotateToken && receipt.bot.canRemove,
        ) { "机器人凭据响应不是当前创建者的活动群机器人" }
        if (command.kind == GroupBotCredentialCommandKind.CREATE) {
            require(receipt.bot.name == command.name) { "机器人凭据响应名称与创建命令不匹配" }
        }
        require(
            receipt.bot.name == receipt.bot.name.trim() &&
                receipt.bot.name.length in 1..GROUP_BOT_NAME_MAX_LENGTH,
        ) { "机器人凭据响应名称非法" }
        require(receipt.bot.name.none(Char::isISOControl)) { "机器人凭据响应名称非法" }
        require(receipt.bot.createdAt >= 0 && (receipt.bot.lastUsedAt ?: 0) >= 0) {
            "机器人凭据响应时间非法"
        }
    }

    private fun messagePath(chatId: String, botId: String): String =
        botPath(chatId, botId) + "/messages"

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
