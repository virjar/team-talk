package com.virjar.tk.shared.client

import com.virjar.tk.protocol.http.GROUP_BOT_NAME_MAX_LENGTH
import com.virjar.tk.protocol.http.GROUP_BOT_WEBHOOK_TOKEN_LENGTH
import com.virjar.tk.protocol.http.GROUP_BOT_WEBHOOK_TOKEN_PREFIX
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

enum class GroupBotCredentialCommandKind(val code: Long) {
    CREATE(1),
    ROTATE(2),
    ;

    companion object {
        fun fromCode(code: Long): GroupBotCredentialCommandKind = entries.firstOrNull { it.code == code }
            ?: error("Unknown group-bot credential command kind")
    }
}

/**
 * 等待用户确认的、唯一 deployment/account 范围的群机器人凭据命令。
 *
 * [webhookToken] 用 256 位熵生成，是用于 ACK 丢失重放的唯一可恢复副本。它只持久化在客户端的私有
 * LocalCache 中；服务器经认证 HTTPS 接收它，并且只存储其 SHA-256。[toString] 防御性地脱敏。
 */
data class PendingGroupBotCredentialCommand(
    val operationId: String,
    val ownerUid: String,
    val kind: GroupBotCredentialCommandKind,
    val chatId: String,
    val botId: String?,
    val name: String?,
    val webhookToken: String,
) {
    fun hasSameIntent(other: PendingGroupBotCredentialCommand): Boolean =
        ownerUid == other.ownerUid && kind == other.kind && chatId == other.chatId &&
            botId == other.botId && name == other.name

    /** 校验跨越 [LocalCache] 实现边界的命令。 */
    fun requireCanonical(): PendingGroupBotCredentialCommand {
        check(normalizedOrNull() == this) { "Pending group-bot credential command is not canonical" }
        return this
    }

    override fun toString(): String =
        "PendingGroupBotCredentialCommand(operationId=$operationId, ownerUid=$ownerUid, " +
            "kind=$kind, chatId=$chatId, botId=$botId, name=$name, webhookToken=<redacted>)"

    private fun normalizedOrNull(): PendingGroupBotCredentialCommand? = runCatching {
        val canonicalOperationId = UUID.fromString(operationId).toString()
            .takeIf { operationId.length == UUID_TEXT_LENGTH && it == operationId }
            ?: return null
        requireSafeId(ownerUid, "ownerUid")
        requireSafeId(chatId, "chatId")
        val canonicalToken = webhookToken.takeIf(::isCanonicalWebhookToken) ?: return null
        when (kind) {
            GroupBotCredentialCommandKind.CREATE -> copy(
                operationId = canonicalOperationId,
                botId = null,
                name = normalizeName(name ?: return null),
                webhookToken = canonicalToken,
            )
            GroupBotCredentialCommandKind.ROTATE -> copy(
                operationId = canonicalOperationId,
                botId = requireSafeId(botId ?: return null, "botId"),
                name = null,
                webhookToken = canonicalToken,
            )
        }
    }.getOrNull()

    companion object {
        private const val UUID_TEXT_LENGTH = 36
        private const val TOKEN_BYTES = 32
        private const val MAX_SAFE_ID_LENGTH = 64
        private val random = SecureRandom()

        fun create(
            operationId: String = UUID.randomUUID().toString(),
            ownerUid: String,
            chatId: String,
            name: String,
            webhookToken: String = newWebhookToken(),
        ): PendingGroupBotCredentialCommand = requireNotNull(
            PendingGroupBotCredentialCommand(
                operationId = operationId,
                ownerUid = ownerUid,
                kind = GroupBotCredentialCommandKind.CREATE,
                chatId = chatId,
                botId = null,
                name = name,
                webhookToken = webhookToken,
            ).normalizedOrNull(),
        ) { "群机器人创建命令参数无效" }

        fun rotate(
            operationId: String = UUID.randomUUID().toString(),
            ownerUid: String,
            chatId: String,
            botId: String,
            webhookToken: String = newWebhookToken(),
        ): PendingGroupBotCredentialCommand = requireNotNull(
            PendingGroupBotCredentialCommand(
                operationId = operationId,
                ownerUid = ownerUid,
                kind = GroupBotCredentialCommandKind.ROTATE,
                chatId = chatId,
                botId = botId,
                name = null,
                webhookToken = webhookToken,
            ).normalizedOrNull(),
        ) { "群机器人轮换命令参数无效" }

        internal fun restore(
            operationId: String,
            ownerUid: String,
            kindCode: Long,
            chatId: String,
            botId: String?,
            name: String?,
            webhookToken: String,
        ): PendingGroupBotCredentialCommand {
            val restored = PendingGroupBotCredentialCommand(
                operationId,
                ownerUid,
                GroupBotCredentialCommandKind.fromCode(kindCode),
                chatId,
                botId,
                name,
                webhookToken,
            )
            check(restored.normalizedOrNull() == restored) {
                "Persisted group-bot credential command is corrupt or non-canonical"
            }
            return restored
        }

        private fun newWebhookToken(): String = GROUP_BOT_WEBHOOK_TOKEN_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

        private fun isCanonicalWebhookToken(token: String): Boolean {
            if (token.length != GROUP_BOT_WEBHOOK_TOKEN_LENGTH ||
                !token.startsWith(GROUP_BOT_WEBHOOK_TOKEN_PREFIX)
            ) return false
            val encoded = token.removePrefix(GROUP_BOT_WEBHOOK_TOKEN_PREFIX)
            val decoded = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return false
            return decoded.size == TOKEN_BYTES &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded
        }

        private fun normalizeName(name: String): String {
            val normalized = name.trim()
            require(normalized.isNotEmpty() && normalized.length <= GROUP_BOT_NAME_MAX_LENGTH)
            require(normalized.none(Char::isISOControl))
            return normalized
        }

        private fun requireSafeId(value: String, field: String): String {
            require(value.length in 1..MAX_SAFE_ID_LENGTH) { "$field 非法" }
            require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "$field 非法" }
            return value
        }
    }
}

class PendingGroupBotCredentialCommandConflictException : IllegalStateException(
    "已有一条结果未知的群机器人凭据命令；必须先恢复并确认该凭据",
)
