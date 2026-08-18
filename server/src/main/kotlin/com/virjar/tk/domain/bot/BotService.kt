package com.virjar.tk.domain.bot

import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class BotAuthenticationException : IllegalArgumentException("机器人凭据无效")

@Serializable
data class CreatedAutomationBot(val bot: AutomationBot, val webhookToken: String)

@Serializable
data class BotDeliveryResult(val chatId: String, val serverSeq: Long, val clientMsgId: String)

/** 受治理通知机器人：服务身份、群白名单、不可恢复 token 与幂等消息发送。 */
class BotService(
    private val repository: BotRepository,
    private val users: UserService,
    private val chatStore: ChatStore,
    private val chats: ChatService,
    private val messages: MessageService,
) {
    private val random = SecureRandom()

    fun list(): List<AutomationBot> = repository.list()

    fun create(name: String): CreatedAutomationBot {
        require(name.isNotBlank()) { "机器人名称不能为空" }
        val account = users.createServiceAccount(name.trim())
        val secret = newToken()
        val now = System.currentTimeMillis()
        val bot = AutomationBot(
            botId = UUID.randomUUID().toString(),
            userUid = account.uid,
            name = account.name,
            status = AutomationBot.STATUS_ACTIVE,
            createdAt = now,
        )
        repository.create(bot, hashToken(secret))
        return CreatedAutomationBot(bot, secret)
    }

    fun rotateToken(botId: String): CreatedAutomationBot {
        val bot = requireBot(botId)
        require(bot.status == AutomationBot.STATUS_ACTIVE) { "机器人已停用" }
        val secret = newToken()
        repository.updateTokenHash(botId, hashToken(secret))
        return CreatedAutomationBot(requireBot(botId), secret)
    }

    suspend fun disable(botId: String) {
        val bot = requireBot(botId)
        repository.setStatus(botId, AutomationBot.STATUS_DISABLED)
        for (chatId in bot.grantedChatIds) {
            repository.revokeGrant(botId, chatId)
            chats.adminRemoveServiceMember(chatId, bot.userUid)
        }
    }

    suspend fun grant(botId: String, chatId: String): AutomationBot {
        val bot = requireBot(botId)
        require(bot.status == AutomationBot.STATUS_ACTIVE) { "机器人已停用" }
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("群聊不存在: $chatId")
        require(chat.chatType == 2) { "机器人只能授权到群聊" }

        // 先提交授权事实，再幂等补齐群成员；崩溃后启动 reconciliation 会继续完成。
        repository.grant(botId, chatId)
        chats.adminAddServiceMember(chatId, bot.userUid)
        return requireBot(botId)
    }

    suspend fun revokeGrant(botId: String, chatId: String): AutomationBot {
        val bot = requireBot(botId)
        // 先撤销权限再移出群；中途崩溃最多留下一个无发送权限的可见成员，不会产生越权。
        repository.revokeGrant(botId, chatId)
        chats.adminRemoveServiceMember(chatId, bot.userUid)
        return requireBot(botId)
    }

    suspend fun deliver(
        botId: String,
        token: String?,
        chatId: String,
        markdown: String,
        idempotencyKey: String,
    ): BotDeliveryResult {
        val bot = authenticate(botId, token)
        require(markdown.isNotBlank()) { "markdown 不能为空" }
        require(markdown.length <= MAX_MARKDOWN_LENGTH) { "markdown 不能超过 $MAX_MARKDOWN_LENGTH 个字符" }
        require(idempotencyKey.length in 1..MAX_IDEMPOTENCY_KEY_LENGTH) { "idempotencyKey 长度必须为 1-$MAX_IDEMPOTENCY_KEY_LENGTH" }
        require(repository.isGranted(botId, chatId)) { "机器人未获该群授权" }

        val clientMsgId = "bot-${botId.take(8)}-${hashText("$chatId:$idempotencyKey").take(32)}"
        val seq = messages.sendMessage(
            bot.userUid,
            Message(
                chatId = chatId,
                clientMsgId = clientMsgId,
                senderUid = bot.userUid,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = buildRichTextBody(markdown),
            ),
        )
        repository.touch(botId, System.currentTimeMillis())
        return BotDeliveryResult(chatId, seq, clientMsgId)
    }

    /** 启动恢复：授权表是事实源，确保所有活动机器人仍是对应群成员。 */
    suspend fun reconcileGrants(): List<String> {
        val failures = mutableListOf<String>()
        for (bot in repository.list().filter { it.status == AutomationBot.STATUS_ACTIVE }) {
            for (chatId in bot.grantedChatIds) {
                runCatching { chats.adminAddServiceMember(chatId, bot.userUid) }
                    .onFailure { failures += "${bot.botId}:$chatId" }
            }
        }
        return failures
    }

    private fun authenticate(botId: String, token: String?): AutomationBot {
        if (token.isNullOrBlank()) throw BotAuthenticationException()
        val bot = repository.findByTokenHash(hashToken(token)) ?: throw BotAuthenticationException()
        if (bot.botId != botId || bot.status != AutomationBot.STATUS_ACTIVE) throw BotAuthenticationException()
        return bot
    }

    private fun requireBot(botId: String): AutomationBot =
        repository.find(botId) ?: throw IllegalArgumentException("机器人不存在: $botId")

    private fun newToken(): String =
        "ttb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun hashToken(token: String): String = hashText(token)

    private fun hashText(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_MARKDOWN_LENGTH = 20_000
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 120
    }
}
