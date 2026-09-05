package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.bot.AutomationBot
import com.virjar.tk.server.domain.bot.BotCredentialCommandReceipt
import com.virjar.tk.server.domain.bot.BotRepository
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.AutomationBotGrants
import com.virjar.tk.server.infra.db.AutomationBots
import com.virjar.tk.server.infra.db.BotCredentialCommands
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedBotRepository(
    private val database: Database,
) : BotRepository {
    override fun create(
        transaction: PgWriteTransactionContext,
        bot: AutomationBot,
        tokenHash: String,
    ): AutomationBot = inWriteTransaction(transaction) {
        AutomationBots.insert {
            it[botId] = bot.botId
            it[userUid] = bot.userUid
            it[name] = bot.name
            it[AutomationBots.tokenHash] = tokenHash
            it[status] = bot.status
            it[managedChatId] = bot.managedChatId
            it[createdByUid] = bot.createdByUid
            it[createdAt] = bot.createdAt
            it[updatedAt] = bot.createdAt
        }
        bot
    }

    override fun list(): List<AutomationBot> = transaction(database) {
        AutomationBots.selectAll().orderBy(AutomationBots.createdAt to SortOrder.DESC)
            .map { it.toBot(listGrantsInternal(it[AutomationBots.botId])) }
    }

    override fun listForChat(chatId: String): List<AutomationBot> = transaction(database) {
        AutomationBotGrants.join(
            otherTable = AutomationBots,
            joinType = JoinType.INNER,
            onColumn = AutomationBotGrants.botId,
            otherColumn = AutomationBots.botId,
        ).selectAll().where {
            AutomationBotGrants.chatId eq chatId
        }.orderBy(AutomationBots.createdAt to SortOrder.DESC)
            .map { it.toBot(listGrantsInternal(it[AutomationBots.botId])) }
    }

    override fun countActiveManagedForChat(transaction: PgReadTransactionContext, chatId: String): Long =
        inReadTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.managedChatId eq chatId) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.count()
    }

    override fun countActiveManagedForCreator(
        transaction: PgReadTransactionContext,
        createdByUid: String,
    ): Long = inReadTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.createdByUid eq createdByUid) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.count()
    }

    override fun countActiveManagedForCreatorInChat(
        transaction: PgReadTransactionContext,
        createdByUid: String,
        chatId: String,
    ): Long = inReadTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.createdByUid eq createdByUid) and
                (AutomationBots.managedChatId eq chatId) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.count()
    }

    override fun lockCreatorQuota(transaction: PgWriteTransactionContext, createdByUid: String) {
        inWriteTransaction(transaction) {
            require(
                Users.selectAll().where { Users.uid eq createdByUid }.forUpdate().singleOrNull() != null,
            ) { "机器人创建者不存在" }
        }
    }

    override fun lockServiceIdentity(transaction: PgWriteTransactionContext, userUid: String) {
        inWriteTransaction(transaction) {
            val row = Users.selectAll().where { Users.uid eq userUid }.forUpdate().singleOrNull()
                ?: throw IllegalStateException("机器人服务身份不存在: $userUid")
            check(row[Users.role] == UserRole.BOT || row[Users.role] == UserRole.SYSTEM) {
                "机器人记录引用了非服务身份: $userUid"
            }
        }
    }

    override fun isServiceIdentity(userUid: String): Boolean = transaction(database) {
        Users.selectAll().where { Users.uid eq userUid }.singleOrNull()?.let { row ->
            row[Users.role] == UserRole.BOT || row[Users.role] == UserRole.SYSTEM
        } == true
    }

    override fun find(botId: String): AutomationBot? = transaction(database) {
        AutomationBots.selectAll().where { AutomationBots.botId eq botId }.singleOrNull()
            ?.toBot(listGrantsInternal(botId))
    }

    override fun findForUpdate(transaction: PgWriteTransactionContext, botId: String): AutomationBot? =
        inWriteTransaction(transaction) {
            AutomationBots.selectAll().where { AutomationBots.botId eq botId }.forUpdate().singleOrNull()
                ?.toBot(listGrantsForUpdateInternal(botId))
        }

    override fun findByTokenHash(tokenHash: String): AutomationBot? = transaction(database) {
        val row = AutomationBots.selectAll().where { AutomationBots.tokenHash eq tokenHash }.singleOrNull()
            ?: return@transaction null
        row.toBot(listGrantsInternal(row[AutomationBots.botId]))
    }

    override fun tokenMatches(
        transaction: PgReadTransactionContext,
        botId: String,
        tokenHash: String,
    ): Boolean = inReadTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.botId eq botId) and (AutomationBots.tokenHash eq tokenHash)
        }.count() == 1L
    }

    override fun updateTokenHash(transaction: PgWriteTransactionContext, botId: String, tokenHash: String) {
        inWriteTransaction(transaction) {
            check(AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[AutomationBots.tokenHash] = tokenHash
                it[updatedAt] = System.currentTimeMillis()
            } == 1) { "Locked bot disappeared during token rotation" }
        }
    }

    override fun findCredentialCommandForUpdate(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        operationId: String,
    ): BotCredentialCommandReceipt? = inWriteTransaction(transaction) {
        BotCredentialCommands.selectAll().where {
            (BotCredentialCommands.actorUid eq actorUid) and
                (BotCredentialCommands.operationId eq operationId)
        }.forUpdate().singleOrNull()?.toBotCredentialCommandReceipt()
    }

    override fun createCredentialCommand(
        transaction: PgWriteTransactionContext,
        receipt: BotCredentialCommandReceipt,
    ) {
        inWriteTransaction(transaction) {
            BotCredentialCommands.insert {
                it[actorUid] = receipt.actorUid
                it[operationId] = receipt.operationId
                it[commandKind] = receipt.commandKind
                it[chatId] = receipt.chatId
                it[botId] = receipt.botId
                it[botUserUid] = receipt.botUserUid
                it[requestFingerprint] = receipt.requestFingerprint
                it[tokenHash] = receipt.tokenHash
                it[createdAt] = receipt.createdAt
            }
        }
    }

    override fun countCredentialCommandsForBot(
        transaction: PgReadTransactionContext,
        botId: String,
    ): Long = inReadTransaction(transaction) {
        BotCredentialCommands.selectAll().where { BotCredentialCommands.botId eq botId }.count()
    }

    override fun setStatus(transaction: PgWriteTransactionContext, botId: String, status: Int) {
        inWriteTransaction(transaction) {
            check(AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[AutomationBots.status] = status
                it[updatedAt] = System.currentTimeMillis()
            } == 1) { "Locked bot disappeared during status mutation" }
        }
    }

    override fun touch(transaction: PgWriteTransactionContext, botId: String, timestamp: Long) {
        inWriteTransaction(transaction) {
            check(AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[lastUsedAt] = timestamp
                it[updatedAt] = timestamp
            } == 1) { "Locked bot disappeared during delivery admission" }
        }
    }

    override fun grant(transaction: PgWriteTransactionContext, botId: String, chatId: String): Boolean =
        inWriteTransaction(transaction) {
            AutomationBotGrants.insertIgnore {
                it[AutomationBotGrants.botId] = botId
                it[AutomationBotGrants.chatId] = chatId
                it[createdAt] = System.currentTimeMillis()
            }.insertedCount == 1
        }

    override fun revokeGrant(transaction: PgWriteTransactionContext, botId: String, chatId: String): Boolean =
        inWriteTransaction(transaction) {
            AutomationBotGrants.deleteWhere {
                (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
            } > 0
        }

    override fun listGrants(botId: String): List<String> = transaction(database) { listGrantsInternal(botId) }

    override fun isGranted(botId: String, chatId: String): Boolean = transaction(database) {
        AutomationBotGrants.selectAll().where {
            (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
        }.count() > 0
    }

    override fun deactivateForChat(transaction: PgWriteTransactionContext, chatId: String) {
        inWriteTransaction(transaction) {
            // ChatService 已经持有聊天行。身份用户、机器人行与授权随后
            // 按此顺序锁定，使每个应用进程共享同一个聚合锁顺序。
            val affectedBotIds = (
                AutomationBots.selectAll().where { AutomationBots.managedChatId eq chatId }
                    .map { it[AutomationBots.botId] } +
                    AutomationBotGrants.selectAll().where { AutomationBotGrants.chatId eq chatId }
                        .map { it[AutomationBotGrants.botId] }
                ).distinct().sorted()
            if (affectedBotIds.isNotEmpty()) {
                val snapshots = AutomationBots.selectAll()
                    .where { AutomationBots.botId inList affectedBotIds }
                    .associate { row -> row[AutomationBots.botId] to row[AutomationBots.userUid] }
                val serviceUids = snapshots.values.distinct().sorted()
                val identities = if (serviceUids.isEmpty()) emptyList() else Users.selectAll()
                    .where { Users.uid inList serviceUids }
                    .orderBy(Users.uid, SortOrder.ASC)
                    .forUpdate()
                    .toList()
                check(identities.size == serviceUids.size) { "机器人服务身份缺失，拒绝解散投影" }
                identities.forEach { row ->
                    check(row[Users.role] == UserRole.BOT || row[Users.role] == UserRole.SYSTEM) {
                        "机器人记录引用了非服务身份，拒绝解散投影"
                    }
                }
                val lockedBots = AutomationBots.selectAll()
                    .where { AutomationBots.botId inList affectedBotIds }
                    .orderBy(AutomationBots.botId, SortOrder.ASC)
                    .forUpdate()
                    .toList()
                check(lockedBots.size == snapshots.size) { "机器人聚合在锁定前发生变化" }
                lockedBots.forEach { row ->
                    check(snapshots[row[AutomationBots.botId]] == row[AutomationBots.userUid]) {
                        "机器人服务身份在锁定前发生变化"
                    }
                }
                AutomationBotGrants.selectAll()
                    .where { AutomationBotGrants.chatId eq chatId }
                    .orderBy(AutomationBotGrants.botId, SortOrder.ASC)
                    .forUpdate()
                    .toList()
                AutomationBots.update({ AutomationBots.managedChatId eq chatId }) {
                    it[status] = AutomationBot.STATUS_DISABLED
                    it[updatedAt] = System.currentTimeMillis()
                }
                AutomationBotGrants.deleteWhere { AutomationBotGrants.chatId eq chatId }
            }
        }
    }

    private fun listGrantsInternal(botId: String): List<String> =
        AutomationBotGrants.selectAll().where { AutomationBotGrants.botId eq botId }
            .map { it[AutomationBotGrants.chatId] }

    private fun listGrantsForUpdateInternal(botId: String): List<String> =
        AutomationBotGrants.selectAll().where { AutomationBotGrants.botId eq botId }
            .orderBy(AutomationBotGrants.chatId, SortOrder.ASC)
            .forUpdate()
            .map { it[AutomationBotGrants.chatId] }

    private inline fun <T> inWriteTransaction(
        context: PgWriteTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedTransaction()
        return block()
    }

    private inline fun <T> inReadTransaction(
        context: PgReadTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedReadTransaction()
        return block()
    }
}

private fun ResultRow.toBot(grants: List<String>) = AutomationBot(
    botId = this[AutomationBots.botId],
    userUid = this[AutomationBots.userUid],
    name = this[AutomationBots.name],
    status = this[AutomationBots.status],
    managedChatId = this[AutomationBots.managedChatId],
    createdByUid = this[AutomationBots.createdByUid],
    grantedChatIds = grants,
    lastUsedAt = this[AutomationBots.lastUsedAt],
    createdAt = this[AutomationBots.createdAt],
)

private fun ResultRow.toBotCredentialCommandReceipt() = BotCredentialCommandReceipt(
    actorUid = this[BotCredentialCommands.actorUid],
    operationId = this[BotCredentialCommands.operationId],
    commandKind = this[BotCredentialCommands.commandKind],
    chatId = this[BotCredentialCommands.chatId],
    botId = this[BotCredentialCommands.botId],
    botUserUid = this[BotCredentialCommands.botUserUid],
    requestFingerprint = this[BotCredentialCommands.requestFingerprint],
    tokenHash = this[BotCredentialCommands.tokenHash],
    createdAt = this[BotCredentialCommands.createdAt],
)
