package com.virjar.tk.server.domain.bot

import com.virjar.tk.protocol.http.GROUP_BOT_WEBHOOK_TOKEN_LENGTH
import com.virjar.tk.protocol.http.GROUP_BOT_WEBHOOK_TOKEN_PREFIX
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal object BotCredentialCommandPolicy {
    fun validateOperationId(operationId: String): String {
        val canonical = runCatching { UUID.fromString(operationId).toString() }.getOrNull()
        require(operationId.length == UUID_TEXT_LENGTH && operationId == canonical) { "operationId 非法" }
        return operationId
    }

    fun validateAndHashClientToken(token: String): String {
        require(token.length == GROUP_BOT_WEBHOOK_TOKEN_LENGTH) { "机器人凭据格式非法" }
        require(token.startsWith(GROUP_BOT_WEBHOOK_TOKEN_PREFIX)) { "机器人凭据格式非法" }
        val encoded = token.removePrefix(GROUP_BOT_WEBHOOK_TOKEN_PREFIX)
        val decoded = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull()
        require(decoded?.size == WEBHOOK_TOKEN_BYTES) { "机器人凭据格式非法" }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded) {
            "机器人凭据格式非法"
        }
        return hashBotText(token)
    }

    fun fingerprint(
        actorUid: String,
        commandKind: Int,
        chatId: String,
        botId: String?,
        normalizedName: String?,
        tokenHash: String,
    ): String = hashBotText(
        listOf(
            FINGERPRINT_VERSION,
            actorUid,
            commandKind.toString(),
            chatId,
            botId.orEmpty(),
            normalizedName.orEmpty(),
            tokenHash,
        ).joinToString("\u0000"),
    )

    fun requireMatch(
        receipt: BotCredentialCommandReceipt,
        commandKind: Int,
        chatId: String,
        botId: String?,
        fingerprint: String,
        tokenHash: String,
    ) {
        if (
            receipt.commandKind != commandKind ||
            receipt.chatId != chatId ||
            (botId != null && receipt.botId != botId) ||
            receipt.requestFingerprint != fingerprint ||
            receipt.tokenHash != tokenHash
        ) {
            throw BotCredentialCommandConflictException("operationId 已绑定到另一条机器人凭据命令")
        }
    }

    const val WEBHOOK_TOKEN_BYTES = 32
    private const val UUID_TEXT_LENGTH = 36
    private const val FINGERPRINT_VERSION = "group-bot-credential-v1"
}

internal fun hashBotText(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

/** 针对投递、轮换与授权变更的、固定大小的进程本地串行化。 */
internal class BotCommandGate(stripeCount: Int = DEFAULT_STRIPE_COUNT) {
    private val stripes = Array(stripeCount.coerceAtLeast(1)) { Mutex() }

    suspend fun <T> withBot(botId: String, block: suspend () -> T): T {
        val mutex = stripes[(botId.hashCode() and Int.MAX_VALUE) % stripes.size]
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 256
    }
}

internal class RetryBotAggregateSnapshotException : RuntimeException()
