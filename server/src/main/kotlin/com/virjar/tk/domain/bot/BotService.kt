package com.virjar.tk.domain.bot

import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.chat.ChatAccess
import com.virjar.tk.domain.chat.ChatAccessDeniedException
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import com.virjar.tk.model.Member
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class BotAuthenticationException : IllegalArgumentException("机器人凭据无效")
class BotAuthorizationException(message: String) : IllegalArgumentException(message)
class BotRequestException(message: String) : IllegalArgumentException(message)
class BotRateLimitException(message: String = "机器人调用过于频繁") : IllegalArgumentException(message)

@Serializable
data class CreatedAutomationBot(val bot: AutomationBot, val webhookToken: String)

@Serializable
data class BotDeliveryResult(val chatId: String, val serverSeq: Long, val clientMsgId: String)

interface BotMessageDelivery {
    suspend fun deliver(
        botId: String,
        token: String?,
        chatId: String,
        markdown: String,
        idempotencyKey: String,
    ): BotDeliveryResult
}

interface GroupBotManagement {
    fun listForGroup(actorUid: String, chatId: String): List<GroupBotSummary>
    suspend fun createForGroup(actorUid: String, chatId: String, name: String): GroupBotCredentials
    fun rotateTokenForGroup(actorUid: String, chatId: String, botId: String): GroupBotCredentials
    suspend fun removeFromGroup(actorUid: String, chatId: String, botId: String)
}

/** 受治理通知机器人：服务身份、群白名单、不可恢复 token 与幂等消息发送。 */
class BotService(
    private val repository: BotRepository,
    private val accounts: BotAccountProvisioner,
    private val access: ChatAccess,
    private val groupMembership: BotGroupMembership,
    private val messageSender: BotMessageSender,
    private val lifecycleGate: ChatLifecycleGate,
    private val rateLimitPerMinute: Int = DEFAULT_RATE_LIMIT_PER_MINUTE,
) : GroupBotManagement, BotMessageDelivery {
    private val random = SecureRandom()
    private val deliveryRateLimiter = BoundedFixedWindowRateLimiter<String>(
        limit = rateLimitPerMinute.coerceAtLeast(1),
        windowMillis = RATE_WINDOW_MILLIS,
        maxTrackedKeys = MAX_TRACKED_DELIVERY_WINDOWS,
    )
    private val creationRateLimiter = BoundedFixedWindowRateLimiter<String>(
        limit = MAX_GROUP_BOT_CREATIONS_PER_MEMBER_PER_HOUR,
        windowMillis = GROUP_CREATION_RATE_WINDOW_MILLIS,
        maxTrackedKeys = MAX_TRACKED_CREATION_WINDOWS,
    )
    private val groupBotCreationMutex = Mutex()

    fun list(): List<AutomationBot> = repository.list()

    fun create(name: String): CreatedAutomationBot = createInternal(name, managedChatId = null, createdByUid = null)

    /** Every active group member may create a bot scoped to that group. */
    override suspend fun createForGroup(actorUid: String, chatId: String, name: String): GroupBotCredentials {
        return groupBotCreationMutex.withLock {
            lifecycleGate.withChat(chatId) {
                val actorRole = requireGroupMember(actorUid, chatId).role
                requireGroupCreationCapacity(actorUid, chatId)
                enforceGroupCreationRate(actorUid)
                val created = createInternal(name, managedChatId = chatId, createdByUid = actorUid)
                try {
                    grantInternal(created.bot, chatId)
                } catch (error: Throwable) {
                    // A credential must never escape for a bot whose membership/grant did not complete.
                    repository.revokeGrant(created.bot.botId, chatId)
                    repository.setStatus(created.bot.botId, AutomationBot.STATUS_DISABLED)
                    runCatching { groupMembership.removeServiceMember(chatId, created.bot.userUid) }
                    throw error
                }
                GroupBotCredentials(
                    bot = requireBot(created.bot.botId).toGroupSummary(actorUid, actorRole, chatId),
                    webhookToken = created.webhookToken,
                )
            }
        }
    }

    override fun listForGroup(actorUid: String, chatId: String): List<GroupBotSummary> {
        val actorRole = requireGroupMember(actorUid, chatId).role
        return repository.listForChat(chatId).map { it.toGroupSummary(actorUid, actorRole, chatId) }
    }

    override fun rotateTokenForGroup(actorUid: String, chatId: String, botId: String): GroupBotCredentials {
        val actorRole = requireGroupMember(actorUid, chatId).role
        val bot = requireGroupOwnedBot(botId, chatId)
        if (bot.createdByUid != actorUid) {
            throw BotAuthorizationException("只有机器人创建者可以轮换 Token")
        }
        val rotated = rotateToken(botId)
        return GroupBotCredentials(rotated.bot.toGroupSummary(actorUid, actorRole, chatId), rotated.webhookToken)
    }

    override suspend fun removeFromGroup(actorUid: String, chatId: String, botId: String) {
        val actor = requireGroupMember(actorUid, chatId)
        val bot = requireGroupOwnedBot(botId, chatId)
        if (bot.createdByUid != actorUid && actor.role < 1) {
            throw BotAuthorizationException("只能移除自己创建的机器人")
        }
        disable(botId)
    }

    private fun createInternal(name: String, managedChatId: String?, createdByUid: String?): CreatedAutomationBot {
        require(name.isNotBlank()) { "机器人名称不能为空" }
        require(name.trim().length <= MAX_NAME_LENGTH) { "机器人名称不能超过 $MAX_NAME_LENGTH 个字符" }
        val account = accounts.createServiceAccount(name.trim())
        val secret = newToken()
        val now = System.currentTimeMillis()
        val bot = AutomationBot(
            botId = UUID.randomUUID().toString(),
            userUid = account.uid,
            name = account.name,
            status = AutomationBot.STATUS_ACTIVE,
            managedChatId = managedChatId,
            createdByUid = createdByUid,
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
            lifecycleGate.withChat(chatId) {
                repository.revokeGrant(botId, chatId)
                groupMembership.removeServiceMember(chatId, bot.userUid)
            }
        }
    }

    suspend fun grant(botId: String, chatId: String): AutomationBot {
        val bot = requireBot(botId)
        return lifecycleGate.withChat(chatId) { grantInternal(bot, chatId) }
    }

    private suspend fun grantInternal(bot: AutomationBot, chatId: String): AutomationBot {
        require(bot.status == AutomationBot.STATUS_ACTIVE) { "机器人已停用" }
        if (bot.managedChatId != null && bot.managedChatId != chatId) {
            throw BotAuthorizationException("群内创建的机器人只能用于其所属群")
        }
        access.requireGroup(chatId)

        // 先提交授权事实，再幂等补齐群成员；崩溃后启动 reconciliation 会继续完成。
        repository.grant(bot.botId, chatId)
        groupMembership.addServiceMember(chatId, bot.userUid)
        return requireBot(bot.botId)
    }

    suspend fun revokeGrant(botId: String, chatId: String): AutomationBot {
        val bot = requireBot(botId)
        lifecycleGate.withChat(chatId) {
            // 先撤销权限再移出群；中途崩溃最多留下一个无发送权限的可见成员，不会产生越权。
            repository.revokeGrant(botId, chatId)
            groupMembership.removeServiceMember(chatId, bot.userUid)
        }
        return requireBot(botId)
    }

    override suspend fun deliver(
        botId: String,
        token: String?,
        chatId: String,
        markdown: String,
        idempotencyKey: String,
    ): BotDeliveryResult {
        val bot = authenticate(botId, token)
        if (markdown.isBlank()) throw BotRequestException("markdown 不能为空")
        if (markdown.length > MAX_MARKDOWN_LENGTH) throw BotRequestException("markdown 不能超过 $MAX_MARKDOWN_LENGTH 个字符")
        if (idempotencyKey.length !in 1..MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BotRequestException("idempotencyKey 长度必须为 1-$MAX_IDEMPOTENCY_KEY_LENGTH")
        }
        if (!repository.isGranted(botId, chatId)) throw BotAuthorizationException("机器人未获该群授权")
        enforceRateLimit(botId)

        val clientMsgId = "bot-${botId.take(8)}-${hashText("$chatId:$idempotencyKey").take(32)}"
        val seq = try {
            messageSender.send(
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
        } catch (e: IllegalArgumentException) {
            // 通知入口只有富文本，没有附件参数；此处的 MessageService 拒绝来自成员/禁言等群权限。
            throw BotAuthorizationException(e.message ?: "机器人当前不能向该群发送")
        }
        repository.touch(botId, System.currentTimeMillis())
        return BotDeliveryResult(chatId, seq, clientMsgId)
    }

    /** 启动恢复：授权表是事实源，确保所有活动机器人仍是对应群成员。 */
    suspend fun recoverGrantMemberships(): List<String> {
        val failures = mutableListOf<String>()
        for (bot in repository.list().filter { it.status == AutomationBot.STATUS_ACTIVE }) {
            bot.managedChatId?.takeIf { it !in bot.grantedChatIds }?.let { chatId ->
                runCatching {
                    lifecycleGate.withChat(chatId) {
                        val current = repository.find(bot.botId)
                        if (
                            current?.status == AutomationBot.STATUS_ACTIVE &&
                            !repository.isGranted(bot.botId, chatId)
                        ) {
                            // The process may have stopped after creating the service identity but
                            // before committing its only group grant. No credential was returned,
                            // so disable the unreachable orphan instead of charging quota forever.
                            repository.setStatus(bot.botId, AutomationBot.STATUS_DISABLED)
                        }
                    }
                }.onFailure { failures += "${bot.botId}:$chatId" }
            }
            for (chatId in bot.grantedChatIds) {
                runCatching {
                    lifecycleGate.withChat(chatId) {
                        val current = repository.find(bot.botId)
                        if (
                            current?.status == AutomationBot.STATUS_ACTIVE &&
                            repository.isGranted(bot.botId, chatId)
                        ) {
                            groupMembership.addServiceMember(chatId, bot.userUid)
                        }
                    }
                }
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

    private fun requireGroupMember(actorUid: String, chatId: String): Member {
        return try {
            access.requireGroupMember(actorUid, chatId, "不是当前群成员")
        } catch (error: ChatAccessDeniedException) {
            val message = if (error.message == "群聊不存在") "机器人只能在群聊中管理" else error.message
            throw BotAuthorizationException(message ?: "无权管理群机器人")
        }
    }

    private fun requireGroupOwnedBot(botId: String, chatId: String): AutomationBot {
        val bot = requireBot(botId)
        if (bot.managedChatId != chatId || !repository.isGranted(botId, chatId)) {
            throw BotAuthorizationException("该机器人不由当前群管理")
        }
        return bot
    }

    private fun requireGroupCreationCapacity(actorUid: String, chatId: String) {
        if (repository.countActiveManagedForChat(chatId) >= MAX_MANAGED_BOTS_PER_GROUP) {
            throw BotRequestException("本群机器人数量已达上限 $MAX_MANAGED_BOTS_PER_GROUP")
        }
        if (
            repository.countActiveManagedForCreatorInChat(actorUid, chatId) >=
            MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP
        ) {
            throw BotRequestException("每位成员在同一群最多创建 $MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP 个机器人")
        }
        if (repository.countActiveManagedForCreator(actorUid) >= MAX_MANAGED_BOTS_PER_CREATOR) {
            throw BotRequestException("每位成员最多管理 $MAX_MANAGED_BOTS_PER_CREATOR 个机器人")
        }
    }

    private fun AutomationBot.toGroupSummary(actorUid: String, actorRole: Int, chatId: String): GroupBotSummary {
        val managedHere = managedChatId?.let(grantedChatIds::contains) == true
        val createdByCaller = managedHere && createdByUid == actorUid
        return GroupBotSummary(
            botId = botId,
            name = name,
            status = status,
            lastUsedAt = lastUsedAt,
            createdAt = createdAt,
            apiPath = "/api/v1/groups/$chatId/bots/$botId/messages",
            groupManaged = managedHere,
            createdByMe = createdByCaller,
            canRotateToken = createdByCaller,
            canRemove = managedHere && (createdByCaller || actorRole >= 1),
        )
    }

    private fun enforceRateLimit(botId: String) {
        if (rateLimitPerMinute <= 0) return
        if (!deliveryRateLimiter.tryAcquire(botId)) throw BotRateLimitException()
    }

    private fun enforceGroupCreationRate(actorUid: String) {
        if (!creationRateLimiter.tryAcquire(actorUid)) {
            throw BotRateLimitException("创建机器人过于频繁，请稍后再试")
        }
    }

    private fun newToken(): String =
        "ttb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun hashToken(token: String): String = hashText(token)

    private fun hashText(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_MARKDOWN_LENGTH = 20_000
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 120
        const val MAX_NAME_LENGTH = 100
        const val MAX_MANAGED_BOTS_PER_GROUP = 20
        const val MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP = 5
        const val MAX_MANAGED_BOTS_PER_CREATOR = 50
        const val MAX_GROUP_BOT_CREATIONS_PER_MEMBER_PER_HOUR = 10
        const val DEFAULT_RATE_LIMIT_PER_MINUTE = 120
        private const val RATE_WINDOW_MILLIS = 60_000L
        private const val GROUP_CREATION_RATE_WINDOW_MILLIS = 60 * 60_000L
        private const val MAX_TRACKED_DELIVERY_WINDOWS = 100_000
        private const val MAX_TRACKED_CREATION_WINDOWS = 50_000
    }
}
