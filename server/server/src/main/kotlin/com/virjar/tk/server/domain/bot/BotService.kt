package com.virjar.tk.server.domain.bot

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.LockedChat
import com.virjar.tk.server.domain.chat.appendMembershipAdditionEvents
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.http.GROUP_BOT_NAME_MAX_LENGTH
import com.virjar.tk.protocol.http.GROUP_BOT_WEBHOOK_TOKEN_PREFIX
import com.virjar.tk.protocol.http.GroupBotCommandReceipt
import com.virjar.tk.protocol.http.GroupBotSummary
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class BotAuthenticationException : IllegalArgumentException("机器人凭据无效")
class BotAuthorizationException(message: String) : IllegalArgumentException(message)
class BotRequestException(message: String) : IllegalArgumentException(message)
class BotRateLimitException(message: String = "机器人调用过于频繁") : IllegalArgumentException(message)
class BotCredentialCommandConflictException(message: String) : IllegalArgumentException(message)
class BotCredentialCommandTerminalException(message: String) : IllegalArgumentException(message)
class BotResourceNotFoundException(message: String) : IllegalArgumentException(message)

@Serializable
data class CreatedAutomationBot(val bot: AutomationBot, val webhookToken: String) {
    override fun toString(): String = "CreatedAutomationBot(bot=$bot, webhookToken=<redacted>)"
}

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
    suspend fun createForGroup(
        actorUid: String,
        chatId: String,
        operationId: String,
        name: String,
        webhookToken: String,
    ): GroupBotCommandReceipt

    suspend fun rotateTokenForGroup(
        actorUid: String,
        chatId: String,
        botId: String,
        operationId: String,
        webhookToken: String,
    ): GroupBotCommandReceipt

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
        val secret = newToken()
        return unitOfWork.write {
            CreatedAutomationBot(
                bot = createInternal(
                    transaction = transaction,
                    normalizedName = normalizedName,
                    managedChatId = null,
                    createdByUid = null,
                    tokenHash = hashToken(secret),
                ),
                webhookToken = secret,
            )
        }
    }

    /** 每个活跃群成员都可以创建一个限定于该群的机器人。 */
    override suspend fun createForGroup(
        actorUid: String,
        chatId: String,
        operationId: String,
        name: String,
        webhookToken: String,
    ): GroupBotCommandReceipt {
        val validatedOperationId = BotCredentialCommandPolicy.validateOperationId(operationId)
        val normalizedName = validateName(name)
        val tokenHash = BotCredentialCommandPolicy.validateAndHashClientToken(webhookToken)
        val fingerprint = BotCredentialCommandPolicy.fingerprint(
            actorUid = actorUid,
            commandKind = BotCredentialCommandReceipt.KIND_CREATE,
            chatId = chatId,
            botId = null,
            normalizedName = normalizedName,
            tokenHash = tokenHash,
        )
        return lifecycleGate.withChat(chatId) {
            unitOfWork.write {
                val targetGroup = lockCredentialTargetGroup(transaction, chatId)
                repository.lockCreatorQuota(transaction, actorUid)
                val replay = repository.findCredentialCommandForUpdate(
                    transaction,
                    actorUid,
                    validatedOperationId,
                )
                if (replay != null) {
                    BotCredentialCommandPolicy.requireMatch(
                        receipt = replay,
                        commandKind = BotCredentialCommandReceipt.KIND_CREATE,
                        chatId = chatId,
                        botId = null,
                        fingerprint = fingerprint,
                        tokenHash = tokenHash,
                    )
                }
                requireUsableCredentialTargetGroup(targetGroup)
                val actor = groupMembership.getActiveMember(transaction, chatId, actorUid)
                    ?: throw BotAuthorizationException("不是当前群成员")
                if (replay != null) {
                    return@write replayCredentialCommand(transaction, replay, actorUid, actor.role, chatId)
                }

                enforceGroupCreationRate(actorUid)
                requireGroupCreationCapacity(transaction, actorUid, chatId)

                val created = createInternal(
                    transaction,
                    normalizedName,
                    managedChatId = chatId,
                    createdByUid = actorUid,
                    tokenHash = tokenHash,
                )
                repository.grant(transaction, created.botId, chatId)
                val addition = groupMembership.addServiceMember(
                    transaction = transaction,
                    chatId = chatId,
                    operatorUid = actorUid,
                    uid = created.userUid,
                ) { facts ->
                    require(facts.chat.chatType == 2) { "机器人只能授权到群聊" }
                    if (facts.operator == null) throw BotAuthorizationException("不是当前群成员")
                }
                appendMembershipAdditionEvents(
                    chat = addition.chat,
                    addedUids = addition.addedUids,
                    activeMemberUids = addition.activeMemberUids,
                )
                if (addition.addedUids.isNotEmpty()) {
                    afterCommit { groupMembership.invalidateCommittedMembershipChange(chatId) }
                }

                val committedBot = created.copy(grantedChatIds = listOf(chatId))
                repository.createCredentialCommand(
                    transaction,
                    BotCredentialCommandReceipt(
                        actorUid = actorUid,
                        operationId = validatedOperationId,
                        commandKind = BotCredentialCommandReceipt.KIND_CREATE,
                        chatId = chatId,
                        botId = committedBot.botId,
                        botUserUid = committedBot.userUid,
                        requestFingerprint = fingerprint,
                        tokenHash = tokenHash,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                GroupBotCommandReceipt(validatedOperationId, committedBot.toGroupSummary(actorUid, actor.role, chatId))
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
        operationId: String,
        webhookToken: String,
    ): GroupBotCommandReceipt = commandGate.withBot(botId) {
        val validatedOperationId = BotCredentialCommandPolicy.validateOperationId(operationId)
        val tokenHash = BotCredentialCommandPolicy.validateAndHashClientToken(webhookToken)
        val fingerprint = BotCredentialCommandPolicy.fingerprint(
            actorUid = actorUid,
            commandKind = BotCredentialCommandReceipt.KIND_ROTATE,
            chatId = chatId,
            botId = botId,
            normalizedName = null,
            tokenHash = tokenHash,
        )
        val snapshot = repository.find(botId)
        lifecycleGate.withChat(chatId) {
            unitOfWork.write {
                val targetGroup = lockCredentialTargetGroup(transaction, chatId)
                repository.lockCreatorQuota(transaction, actorUid)
                val replay = repository.findCredentialCommandForUpdate(
                    transaction,
                    actorUid,
                    validatedOperationId,
                )
                if (replay != null) {
                    BotCredentialCommandPolicy.requireMatch(
                        receipt = replay,
                        commandKind = BotCredentialCommandReceipt.KIND_ROTATE,
                        chatId = chatId,
                        botId = botId,
                        fingerprint = fingerprint,
                        tokenHash = tokenHash,
                    )
                }
                requireUsableCredentialTargetGroup(targetGroup)
                val actor = groupMembership.getActiveMember(transaction, chatId, actorUid)
                    ?: throw BotAuthorizationException("不是当前群成员")
                if (replay != null) {
                    return@write replayCredentialCommand(transaction, replay, actorUid, actor.role, chatId)
                }
                val currentSnapshot = snapshot ?: throw BotResourceNotFoundException("机器人不存在: $botId")
                repository.lockServiceIdentity(transaction, currentSnapshot.userUid)
                val bot = requireBotForUpdate(transaction, botId)
                requireBotIdentity(bot, currentSnapshot.userUid)
                if (bot.managedChatId != chatId) {
                    throw BotAuthorizationException("该机器人不由当前群管理")
                }
                if (bot.status != AutomationBot.STATUS_ACTIVE || chatId !in bot.grantedChatIds) {
                    throw BotCredentialCommandTerminalException("该机器人已被移除或停用")
                }
                if (bot.createdByUid != actorUid) {
                    throw BotAuthorizationException("只有机器人创建者可以轮换 Token")
                }

                if (repository.countCredentialCommandsForBot(transaction, botId) >= MAX_CREDENTIAL_COMMANDS_PER_BOT) {
                    throw BotCredentialCommandTerminalException(
                        "机器人凭据轮换次数已达上限，请移除后重新创建机器人",
                    )
                }
                repository.updateTokenHash(transaction, botId, tokenHash)
                repository.createCredentialCommand(
                    transaction,
                    BotCredentialCommandReceipt(
                        actorUid = actorUid,
                        operationId = validatedOperationId,
                        commandKind = BotCredentialCommandReceipt.KIND_ROTATE,
                        chatId = chatId,
                        botId = botId,
                        botUserUid = bot.userUid,
                        requestFingerprint = fingerprint,
                        tokenHash = tokenHash,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                GroupBotCommandReceipt(validatedOperationId, bot.toGroupSummary(actorUid, actor.role, chatId))
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
        transaction: PgWriteTransactionContext,
        normalizedName: String,
        managedChatId: String?,
        createdByUid: String?,
        tokenHash: String,
    ): AutomationBot {
        val account = accounts.createServiceAccount(transaction, normalizedName)
        repository.lockServiceIdentity(transaction, account.uid)
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
        repository.create(transaction, bot, tokenHash)
        return bot
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
                appendMembershipAdditionEvents(
                    chat = addition.chat,
                    addedUids = addition.addedUids,
                    activeMemberUids = addition.activeMemberUids,
                )
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
                // 消息准入已经拥有投影权威与 Chat。在服务 User -> Bot/授权（grant）锁下重新
                // 读取每一项机器人能力，使另一个进程上已完成的轮换、停用或撤回能在此请求
                // 分配 seq 之前关闭。
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
                                appendMembershipAdditionEvents(
                                    chat = addition.chat,
                                    addedUids = addition.addedUids,
                                    activeMemberUids = addition.activeMemberUids,
                                )
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

    /** 调用方已经持有有界的机器人命令闸门。 */
    private suspend fun <T> withStableBotChats(
        botId: String,
        block: PgWriteScope.(AutomationBot, Map<String, LockedChat>, Set<String>) -> T,
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
                // 一个并发进程在机器人锁定之前提交了新的聊天边。这个工作单元已无变更地
                // 回滚；扩大闸门集合并重试。
            }
        }
        error("机器人聚合持续变化，请重试: $botId")
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
        // CHAT_DELETED 已经原子地移除了每个按聊天区分的本地投影。仅当没有发出成员/
        // 聊天墓碑时，才使用更窄的会话墓碑。
        if (cleanup.conversationDeleted && !cleanup.membershipDeactivated) {
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
        repository.find(botId) ?: throw BotResourceNotFoundException("机器人不存在: $botId")

    private fun requireBotForUpdate(transaction: PgWriteTransactionContext, botId: String): AutomationBot =
        repository.findForUpdate(transaction, botId)
            ?: throw BotResourceNotFoundException("机器人不存在: $botId")

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

    private fun lockCredentialTargetGroup(
        transaction: PgWriteTransactionContext,
        chatId: String,
    ): LockedChat? = groupMembership.lockChats(transaction, listOf(chatId), requireActive = false)[chatId]

    private fun requireUsableCredentialTargetGroup(target: LockedChat?) {
        if (target == null) throw BotResourceNotFoundException("目标群不存在")
        if (target.chat.chatType != 2) throw BotAuthorizationException("机器人只能在群聊中管理")
        if (!target.active) throw BotCredentialCommandTerminalException("目标群已不可用")
    }

    private fun requireGroupOwnedBot(bot: AutomationBot, chatId: String) {
        if (bot.managedChatId != chatId || chatId !in bot.grantedChatIds) {
            throw BotAuthorizationException("该机器人不由当前群管理")
        }
    }

    private fun replayCredentialCommand(
        transaction: PgWriteTransactionContext,
        receipt: BotCredentialCommandReceipt,
        actorUid: String,
        actorRole: Int,
        chatId: String,
    ): GroupBotCommandReceipt {
        repository.lockServiceIdentity(transaction, receipt.botUserUid)
        val bot = requireBotForUpdate(transaction, receipt.botId)
        requireBotIdentity(bot, receipt.botUserUid)
        check(bot.createdByUid == actorUid && bot.managedChatId == chatId) {
            "机器人凭据收据引用了错误的机器人归属"
        }
        if (
            bot.status != AutomationBot.STATUS_ACTIVE ||
            chatId !in bot.grantedChatIds ||
            !repository.tokenMatches(transaction, bot.botId, receipt.tokenHash)
        ) {
            throw BotCredentialCommandTerminalException("该凭据命令已被后续轮换或停用")
        }
        return GroupBotCommandReceipt(
            operationId = receipt.operationId,
            bot = bot.toGroupSummary(actorUid, actorRole, chatId),
        )
    }

    private fun requireGroupCreationCapacity(
        transaction: PgReadTransactionContext,
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
        require(normalized.none(Char::isISOControl)) { "机器人名称包含非法控制字符" }
        return normalized
    }

    private fun newToken(): String =
        GROUP_BOT_WEBHOOK_TOKEN_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(BotCredentialCommandPolicy.WEBHOOK_TOKEN_BYTES).also(random::nextBytes),
            )

    private fun hashToken(token: String): String = hashBotText(token)

    private fun hashText(value: String): String = hashBotText(value)

    companion object {
        const val MAX_MARKDOWN_LENGTH = 20_000
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 120
        const val MAX_NAME_LENGTH = GROUP_BOT_NAME_MAX_LENGTH
        const val MAX_MANAGED_BOTS_PER_GROUP = 20
        const val MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP = 5
        const val MAX_MANAGED_BOTS_PER_CREATOR = 50
        const val MAX_GROUP_BOT_CREATIONS_PER_MEMBER_PER_HOUR = 10
        const val MAX_CREDENTIAL_COMMANDS_PER_BOT = 256
        const val DEFAULT_RATE_LIMIT_PER_MINUTE = 120
        private const val RATE_WINDOW_MILLIS = 60_000L
        private const val GROUP_CREATION_RATE_WINDOW_MILLIS = 60 * 60_000L
        private const val MAX_TRACKED_DELIVERY_WINDOWS = 100_000
        private const val MAX_TRACKED_CREATION_WINDOWS = 50_000
        private const val MAX_AGGREGATE_SNAPSHOT_RETRIES = 8
    }
}
