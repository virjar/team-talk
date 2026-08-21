package com.virjar.tk.domain.bot

import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.chat.ChatAccess
import com.virjar.tk.domain.chat.ChatAccessDeniedException
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.chat.GroupMemberAddition
import com.virjar.tk.domain.chat.LockedChat
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.transaction.PgWriteScope
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import com.virjar.tk.model.Member
import com.virjar.tk.model.Message
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
    suspend fun listForGroup(actorUid: String, chatId: String): List<GroupBotSummary>
    suspend fun createForGroup(actorUid: String, chatId: String, name: String): GroupBotCredentials
    suspend fun rotateTokenForGroup(actorUid: String, chatId: String, botId: String): GroupBotCredentials
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
    private val unitOfWork: PgUnitOfWork,
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
    private val commandGate = BotCommandGate()

    fun list(): List<AutomationBot> = repository.list()

    suspend fun create(name: String): CreatedAutomationBot {
        val normalizedName = validateName(name)
        return unitOfWork.write {
            createInternal(transaction, normalizedName, managedChatId = null, createdByUid = null)
        }
    }

    /** Every active group member may create a bot scoped to that group. */
    override suspend fun createForGroup(actorUid: String, chatId: String, name: String): GroupBotCredentials {
        val normalizedName = validateName(name)
        enforceGroupCreationRate(actorUid)
        return lifecycleGate.withChat(chatId) {
            unitOfWork.write {
                val chat = groupMembership.lockChats(
                    transaction,
                    listOf(chatId),
                    requireActive = true,
                ).getValue(chatId).chat
                if (chat.chatType != 2) throw BotAuthorizationException("机器人只能在群聊中管理")

                repository.lockCreatorQuota(transaction, actorUid)
                val actor = groupMembership.getActiveMember(transaction, chatId, actorUid)
                    ?: throw BotAuthorizationException("不是当前群成员")
                requireGroupCreationCapacity(transaction, actorUid, chatId)

                val created = createInternal(
                    transaction,
                    normalizedName,
                    managedChatId = chatId,
                    createdByUid = actorUid,
                )
                repository.grant(transaction, created.bot.botId, chatId)
                val addition = groupMembership.addServiceMember(
                    transaction = transaction,
                    chatId = chatId,
                    operatorUid = actorUid,
                    uid = created.bot.userUid,
                ) { facts ->
                    require(facts.chat.chatType == 2) { "机器人只能授权到群聊" }
                    if (facts.operator == null) throw BotAuthorizationException("不是当前群成员")
                }
                appendMembershipAddition(addition)
                if (addition.addedUids.isNotEmpty()) {
                    afterCommit { groupMembership.invalidateCommittedMembershipChange(chatId) }
                }

                val committedBot = created.bot.copy(grantedChatIds = listOf(chatId))
                GroupBotCredentials(
                    bot = committedBot.toGroupSummary(actorUid, actor.role, chatId),
                    webhookToken = created.webhookToken,
                )
            }
        }
    }

    override suspend fun listForGroup(actorUid: String, chatId: String): List<GroupBotSummary> =
        withContext(Dispatchers.IO) {
            try {
                access.readAsGroupMember(actorUid, chatId, "不是当前群成员") { _, actor ->
                    repository.listForChat(chatId).map { it.toGroupSummary(actorUid, actor.role, chatId) }
                }
            } catch (error: ChatAccessDeniedException) {
                val message = if (error.message == "群聊不存在") "机器人只能在群聊中管理" else error.message
                throw BotAuthorizationException(message ?: "无权管理群机器人")
            }
        }

    override suspend fun rotateTokenForGroup(
        actorUid: String,
        chatId: String,
        botId: String,
    ): GroupBotCredentials = commandGate.withBot(botId) {
        val snapshot = requireBot(botId)
        lifecycleGate.withChat(chatId) {
            unitOfWork.write {
                val chat = groupMembership.lockChats(
                    transaction,
                    listOf(chatId),
                    requireActive = true,
                ).getValue(chatId).chat
                require(chat.chatType == 2) { "机器人只能在群聊中管理" }
                repository.lockServiceIdentity(transaction, snapshot.userUid)
                val bot = requireBotForUpdate(transaction, botId)
                requireBotIdentity(bot, snapshot.userUid)
                val actor = groupMembership.getActiveMember(transaction, chatId, actorUid)
                    ?: throw BotAuthorizationException("不是当前群成员")
                requireGroupOwnedBot(bot, chatId)
                if (bot.createdByUid != actorUid) {
                    throw BotAuthorizationException("只有机器人创建者可以轮换 Token")
                }
                require(bot.status == AutomationBot.STATUS_ACTIVE) { "机器人已停用" }
                val secret = newToken()
                repository.updateTokenHash(transaction, botId, hashToken(secret))
                GroupBotCredentials(bot.toGroupSummary(actorUid, actor.role, chatId), secret)
            }
        }
    }

    override suspend fun removeFromGroup(actorUid: String, chatId: String, botId: String) {
        commandGate.withBot(botId) {
            val snapshot = requireBot(botId)
            lifecycleGate.withChat(chatId) {
                unitOfWork.write {
                    val lockedChat = groupMembership.lockChats(
                        transaction,
                        listOf(chatId),
                        requireActive = true,
                    ).getValue(chatId)
                    val chat = lockedChat.chat
                    require(chat.chatType == 2) { "机器人只能在群聊中管理" }
                    repository.lockServiceIdentity(transaction, snapshot.userUid)
                    val bot = requireBotForUpdate(transaction, botId)
                    requireBotIdentity(bot, snapshot.userUid)
                    val actor = groupMembership.getActiveMember(transaction, chatId, actorUid)
                        ?: throw BotAuthorizationException("不是当前群成员")
                    requireGroupOwnedBot(bot, chatId)
                    if (bot.createdByUid != actorUid && actor.role < 1) {
                        throw BotAuthorizationException("只能移除自己创建的机器人")
                    }
                    repository.setStatus(transaction, botId, AutomationBot.STATUS_DISABLED)
                    repository.revokeGrant(transaction, botId, chatId)
                    removeMembershipProjection(bot, chatId, lockedChat)
                }
            }
        }
    }

    private fun createInternal(
        transaction: PgTransactionContext,
        normalizedName: String,
        managedChatId: String?,
        createdByUid: String?,
    ): CreatedAutomationBot {
        val account = accounts.createServiceAccount(transaction, normalizedName)
        repository.lockServiceIdentity(transaction, account.uid)
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
        repository.create(transaction, bot, hashToken(secret))
        return CreatedAutomationBot(bot, secret)
    }

    suspend fun rotateToken(botId: String): CreatedAutomationBot = commandGate.withBot(botId) {
        val snapshot = requireBot(botId)
        val chatIds = listOfNotNull(snapshot.managedChatId)
        lifecycleGate.withChats(*chatIds.toTypedArray()) {
            unitOfWork.write {
                if (chatIds.isNotEmpty()) {
                    groupMembership.lockChats(transaction, chatIds, requireActive = true)
                }
                repository.lockServiceIdentity(transaction, snapshot.userUid)
                val bot = requireBotForUpdate(transaction, botId)
                requireBotIdentity(bot, snapshot.userUid)
                require(bot.status == AutomationBot.STATUS_ACTIVE) { "机器人已停用" }
                require(bot.managedChatId == snapshot.managedChatId) { "机器人归属已变更" }
                val secret = newToken()
                repository.updateTokenHash(transaction, botId, hashToken(secret))
                CreatedAutomationBot(bot, secret)
            }
        }
    }

    suspend fun disable(botId: String): AutomationBot = commandGate.withBot(botId) {
        withStableBotChats(botId) { bot, lockedChats, actualChatIds ->
            repository.setStatus(transaction, botId, AutomationBot.STATUS_DISABLED)
            actualChatIds.sorted().forEach { chatId ->
                repository.revokeGrant(transaction, botId, chatId)
                removeMembershipProjection(bot, chatId, lockedChats[chatId])
            }
            bot.copy(status = AutomationBot.STATUS_DISABLED, grantedChatIds = emptyList())
        }
    }

    suspend fun grant(botId: String, chatId: String): AutomationBot = commandGate.withBot(botId) {
        val snapshot = requireBot(botId)
        lifecycleGate.withChat(chatId) {
            unitOfWork.write {
                val chat = groupMembership.lockChats(
                    transaction,
                    listOf(chatId),
                    requireActive = true,
                ).getValue(chatId).chat
                require(chat.chatType == 2) { "机器人只能授权到群聊" }
                repository.lockServiceIdentity(transaction, snapshot.userUid)
                val bot = requireBotForUpdate(transaction, botId)
                requireBotIdentity(bot, snapshot.userUid)
                require(bot.status == AutomationBot.STATUS_ACTIVE) { "机器人已停用" }
                if (bot.managedChatId != null && bot.managedChatId != chatId) {
                    throw BotAuthorizationException("群内创建的机器人只能用于其所属群")
                }
                repository.grant(transaction, botId, chatId)
                val addition = groupMembership.addServiceMember(
                    transaction = transaction,
                    chatId = chatId,
                    operatorUid = bot.userUid,
                    uid = bot.userUid,
                ) { facts ->
                    require(facts.chat.chatType == 2) { "机器人只能授权到群聊" }
                }
                appendMembershipAddition(addition)
                if (addition.addedUids.isNotEmpty()) {
                    afterCommit { groupMembership.invalidateCommittedMembershipChange(chatId) }
                }
                bot.copy(grantedChatIds = (bot.grantedChatIds + chatId).distinct())
            }
        }
    }

    suspend fun revokeGrant(botId: String, chatId: String): AutomationBot = commandGate.withBot(botId) {
        val snapshot = requireBot(botId)
        lifecycleGate.withChat(chatId) {
            unitOfWork.write {
                val lockedChats = groupMembership.lockChats(transaction, listOf(chatId), requireActive = false)
                repository.lockServiceIdentity(transaction, snapshot.userUid)
                val bot = requireBotForUpdate(transaction, botId)
                requireBotIdentity(bot, snapshot.userUid)
                repository.revokeGrant(transaction, botId, chatId)
                val disabled = bot.managedChatId == chatId
                if (disabled) repository.setStatus(transaction, botId, AutomationBot.STATUS_DISABLED)
                removeMembershipProjection(bot, chatId, lockedChats[chatId])
                bot.copy(
                    status = if (disabled) AutomationBot.STATUS_DISABLED else bot.status,
                    grantedChatIds = bot.grantedChatIds - chatId,
                )
            }
        }
    }

    override suspend fun deliver(
        botId: String,
        token: String?,
        chatId: String,
        markdown: String,
        idempotencyKey: String,
    ): BotDeliveryResult = commandGate.withBot(botId) {
        val presentedToken = token ?: throw BotAuthenticationException()
        val presentedTokenHash = hashToken(presentedToken)
        val bot = authenticate(botId, presentedToken)
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
            ) { transaction ->
                // Message admission already owns projection authority and Chat. Re-read every bot
                // capability under service User -> Bot/grant locks so a completed rotation,
                // disable or revoke on another process closes before this request can allocate seq.
                repository.lockServiceIdentity(transaction, bot.userUid)
                val current = repository.findForUpdate(transaction, botId)
                    ?: throw BotAuthenticationException()
                requireBotIdentity(current, bot.userUid)
                if (
                    current.status != AutomationBot.STATUS_ACTIVE ||
                    !repository.tokenMatches(transaction, botId, presentedTokenHash)
                ) {
                    throw BotAuthenticationException()
                }
                if (chatId !in current.grantedChatIds) {
                    throw BotAuthorizationException("机器人未获该群授权")
                }
                repository.touch(transaction, botId, System.currentTimeMillis())
            }
        } catch (e: BotAuthenticationException) {
            throw e
        } catch (e: BotAuthorizationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // 通知入口只有富文本，没有附件参数；此处的 MessageService 拒绝来自成员/禁言等群权限。
            throw BotAuthorizationException(e.message ?: "机器人当前不能向该群发送")
        }
        BotDeliveryResult(chatId, seq, clientMsgId)
    }

    /** 启动恢复：以 grant + member/Conversation/mute 投影并集清理负状态。 */
    suspend fun recoverGrantMemberships(): List<String> {
        val failures = mutableListOf<String>()
        repository.list().map(AutomationBot::botId).distinct().forEach { botId ->
            try {
                commandGate.withBot(botId) {
                    withStableBotChats(botId) { bot, lockedChats, actualChatIds ->
                        val managedChat = bot.managedChatId
                        val managedChatIsUsable = managedChat == null || (
                            lockedChats[managedChat]?.active == true &&
                                lockedChats.getValue(managedChat).chat.chatType == 2 &&
                                managedChat in bot.grantedChatIds
                            )
                        val shouldDisable =
                            bot.status != AutomationBot.STATUS_ACTIVE || !managedChatIsUsable
                        if (shouldDisable && bot.status != AutomationBot.STATUS_DISABLED) {
                            repository.setStatus(transaction, botId, AutomationBot.STATUS_DISABLED)
                        }

                        val desiredChatIds = if (shouldDisable) {
                            emptySet()
                        } else {
                            bot.grantedChatIds.filterTo(linkedSetOf()) { chatId ->
                                lockedChats[chatId]?.let { it.active && it.chat.chatType == 2 } == true
                            }
                        }

                        actualChatIds.sorted().forEach { chatId ->
                            if (chatId in desiredChatIds) {
                                val addition = groupMembership.addServiceMember(
                                    transaction = transaction,
                                    chatId = chatId,
                                    operatorUid = bot.userUid,
                                    uid = bot.userUid,
                                ) { facts ->
                                    require(facts.chat.chatType == 2) { "机器人只能授权到群聊" }
                                }
                                appendMembershipAddition(addition)
                                if (addition.addedUids.isNotEmpty()) {
                                    afterCommit {
                                        groupMembership.invalidateCommittedMembershipChange(chatId)
                                    }
                                }
                            } else {
                                repository.revokeGrant(transaction, botId, chatId)
                                removeMembershipProjection(bot, chatId, lockedChats[chatId])
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                when (error) {
                    is CancellationException -> throw error
                    is Error -> throw error
                    else -> failures += botId
                }
            }
        }
        return failures
    }

    /** Caller already owns the bounded bot command gate. */
    private suspend fun <T> withStableBotChats(
        botId: String,
        block: suspend PgWriteScope.(AutomationBot, Map<String, LockedChat>, Set<String>) -> T,
    ): T {
        repeat(MAX_AGGREGATE_SNAPSHOT_RETRIES) {
            val snapshot = requireBot(botId)
            val candidateChatIds = (
                snapshot.grantedChatIds +
                    groupMembership.projectedChatIds(snapshot.userUid) +
                    listOfNotNull(snapshot.managedChatId)
                ).toSortedSet()
            try {
                return lifecycleGate.withChats(*candidateChatIds.toTypedArray()) {
                    unitOfWork.write {
                        val lockedChats = groupMembership.lockChats(
                            transaction,
                            candidateChatIds,
                            requireActive = false,
                        )
                        repository.lockServiceIdentity(transaction, snapshot.userUid)
                        val current = requireBotForUpdate(transaction, botId)
                        requireBotIdentity(current, snapshot.userUid)
                        val projectedChatIds = groupMembership.projectedChatIds(transaction, current.userUid)
                        val actualChatIds = (
                            current.grantedChatIds + projectedChatIds + listOfNotNull(current.managedChatId)
                            ).toSet()
                        if (!candidateChatIds.containsAll(actualChatIds)) {
                            throw RetryBotAggregateSnapshotException()
                        }
                        block(current, lockedChats, actualChatIds)
                    }
                }
            } catch (_: RetryBotAggregateSnapshotException) {
                // A concurrent process committed a new chat edge before the bot lock. This UoW
                // rolled back without changes; expand the gate set and retry.
            }
        }
        error("机器人聚合持续变化，请重试: $botId")
    }

    private fun PgWriteScope.appendMembershipAddition(addition: GroupMemberAddition) {
        if (addition.addedUids.isEmpty()) return
        addition.addedUids.forEach { uid ->
            appendEvent(uid, NotifyType.CHAT_CREATED, addition.chat)
        }
        addition.activeMemberUids.forEach { uid ->
            appendEvent(uid, NotifyType.MEMBER_ADDED, addition.chat)
        }
    }

    private fun PgWriteScope.removeMembershipProjection(
        bot: AutomationBot,
        chatId: String,
        lockedChat: LockedChat?,
    ) {
        val cleanup = groupMembership.cleanupServiceMemberProjection(
            transaction = transaction,
            chatId = chatId,
            uid = bot.userUid,
            lockedChat = lockedChat,
        ) ?: return

        if (cleanup.membershipDeactivated) {
            appendEvent(bot.userUid, NotifyType.CHAT_DELETED, cleanup.chat)
            cleanup.remainingMemberUids.forEach { uid ->
                appendEvent(uid, NotifyType.MEMBER_REMOVED, cleanup.chat)
            }
        }
        if (cleanup.conversationDeleted) {
            appendEvent(
                bot.userUid,
                NotifyType.CONVERSATION_DELETED,
                Conversation(chatId = cleanup.chat.chatId, chatType = 0),
            )
        }
        afterCommit { groupMembership.invalidateCommittedMembershipChange(chatId) }
    }

    private fun authenticate(botId: String, token: String?): AutomationBot {
        if (token.isNullOrBlank()) throw BotAuthenticationException()
        val bot = repository.findByTokenHash(hashToken(token)) ?: throw BotAuthenticationException()
        if (bot.botId != botId || bot.status != AutomationBot.STATUS_ACTIVE) throw BotAuthenticationException()
        if (!repository.isServiceIdentity(bot.userUid)) throw BotAuthenticationException()
        return bot
    }

    private fun requireBot(botId: String): AutomationBot =
        repository.find(botId) ?: throw IllegalArgumentException("机器人不存在: $botId")

    private fun requireBotForUpdate(transaction: PgTransactionContext, botId: String): AutomationBot =
        repository.findForUpdate(transaction, botId)
            ?: throw IllegalArgumentException("机器人不存在: $botId")

    private fun requireBotIdentity(bot: AutomationBot, expectedUserUid: String) {
        check(bot.userUid == expectedUserUid) { "机器人服务身份在锁定前发生变化" }
    }

    private suspend fun requireGroupMember(actorUid: String, chatId: String): Member {
        return try {
            access.requireGroupMember(actorUid, chatId, "不是当前群成员")
        } catch (error: ChatAccessDeniedException) {
            val message = if (error.message == "群聊不存在") "机器人只能在群聊中管理" else error.message
            throw BotAuthorizationException(message ?: "无权管理群机器人")
        }
    }

    private fun requireGroupOwnedBot(bot: AutomationBot, chatId: String) {
        if (bot.managedChatId != chatId || chatId !in bot.grantedChatIds) {
            throw BotAuthorizationException("该机器人不由当前群管理")
        }
    }

    private fun requireGroupCreationCapacity(
        transaction: PgTransactionContext,
        actorUid: String,
        chatId: String,
    ) {
        if (repository.countActiveManagedForChat(transaction, chatId) >= MAX_MANAGED_BOTS_PER_GROUP) {
            throw BotRequestException("本群机器人数量已达上限 $MAX_MANAGED_BOTS_PER_GROUP")
        }
        if (
            repository.countActiveManagedForCreatorInChat(transaction, actorUid, chatId) >=
            MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP
        ) {
            throw BotRequestException("每位成员在同一群最多创建 $MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP 个机器人")
        }
        if (repository.countActiveManagedForCreator(transaction, actorUid) >= MAX_MANAGED_BOTS_PER_CREATOR) {
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

    private fun validateName(name: String): String {
        require(name.isNotBlank()) { "机器人名称不能为空" }
        val normalized = name.trim()
        require(normalized.length <= MAX_NAME_LENGTH) { "机器人名称不能超过 $MAX_NAME_LENGTH 个字符" }
        return normalized
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
        private const val MAX_AGGREGATE_SNAPSHOT_RETRIES = 8
    }
}

/** Fixed-size process-local serialization for delivery, rotation and authorization changes. */
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

private class RetryBotAggregateSnapshotException : RuntimeException()
