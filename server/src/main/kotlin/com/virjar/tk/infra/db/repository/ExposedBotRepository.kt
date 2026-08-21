package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.bot.AutomationBot
import com.virjar.tk.domain.bot.BotRepository
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.AutomationBotGrants
import com.virjar.tk.infra.db.AutomationBots
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.UserRole
import org.jetbrains.exposed.sql.ResultRow
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

class ExposedBotRepository : BotRepository {
    override fun create(
        transaction: PgTransactionContext,
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

    override fun list(): List<AutomationBot> = transaction {
        AutomationBots.selectAll().orderBy(AutomationBots.createdAt to SortOrder.DESC)
            .map { it.toBot(listGrantsInternal(it[AutomationBots.botId])) }
    }

    override fun listForChat(chatId: String): List<AutomationBot> = transaction {
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

    override fun countActiveManagedForChat(transaction: PgTransactionContext, chatId: String): Long =
        inWriteTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.managedChatId eq chatId) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.count()
    }

    override fun countActiveManagedForCreator(
        transaction: PgTransactionContext,
        createdByUid: String,
    ): Long = inWriteTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.createdByUid eq createdByUid) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.count()
    }

    override fun countActiveManagedForCreatorInChat(
        transaction: PgTransactionContext,
        createdByUid: String,
        chatId: String,
    ): Long = inWriteTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.createdByUid eq createdByUid) and
                (AutomationBots.managedChatId eq chatId) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.count()
    }

    override fun lockCreatorQuota(transaction: PgTransactionContext, createdByUid: String) {
        inWriteTransaction(transaction) {
            require(
                Users.selectAll().where { Users.uid eq createdByUid }.forUpdate().singleOrNull() != null,
            ) { "机器人创建者不存在" }
        }
    }

    override fun lockServiceIdentity(transaction: PgTransactionContext, userUid: String) {
        inWriteTransaction(transaction) {
            val row = Users.selectAll().where { Users.uid eq userUid }.forUpdate().singleOrNull()
                ?: throw IllegalStateException("机器人服务身份不存在: $userUid")
            check(row[Users.role] == UserRole.BOT || row[Users.role] == UserRole.SYSTEM) {
                "机器人记录引用了非服务身份: $userUid"
            }
        }
    }

    override fun isServiceIdentity(userUid: String): Boolean = transaction {
        Users.selectAll().where { Users.uid eq userUid }.singleOrNull()?.let { row ->
            row[Users.role] == UserRole.BOT || row[Users.role] == UserRole.SYSTEM
        } == true
    }

    override fun find(botId: String): AutomationBot? = transaction {
        AutomationBots.selectAll().where { AutomationBots.botId eq botId }.singleOrNull()
            ?.toBot(listGrantsInternal(botId))
    }

    override fun findForUpdate(transaction: PgTransactionContext, botId: String): AutomationBot? =
        inWriteTransaction(transaction) {
            AutomationBots.selectAll().where { AutomationBots.botId eq botId }.forUpdate().singleOrNull()
                ?.toBot(listGrantsForUpdateInternal(botId))
        }

    override fun findByTokenHash(tokenHash: String): AutomationBot? = transaction {
        val row = AutomationBots.selectAll().where { AutomationBots.tokenHash eq tokenHash }.singleOrNull()
            ?: return@transaction null
        row.toBot(listGrantsInternal(row[AutomationBots.botId]))
    }

    override fun tokenMatches(
        transaction: PgTransactionContext,
        botId: String,
        tokenHash: String,
    ): Boolean = inWriteTransaction(transaction) {
        AutomationBots.selectAll().where {
            (AutomationBots.botId eq botId) and (AutomationBots.tokenHash eq tokenHash)
        }.count() == 1L
    }

    override fun updateTokenHash(transaction: PgTransactionContext, botId: String, tokenHash: String) {
        inWriteTransaction(transaction) {
            check(AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[AutomationBots.tokenHash] = tokenHash
                it[updatedAt] = System.currentTimeMillis()
            } == 1) { "Locked bot disappeared during token rotation" }
        }
    }

    override fun setStatus(transaction: PgTransactionContext, botId: String, status: Int) {
        inWriteTransaction(transaction) {
            check(AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[AutomationBots.status] = status
                it[updatedAt] = System.currentTimeMillis()
            } == 1) { "Locked bot disappeared during status mutation" }
        }
    }

    override fun touch(transaction: PgTransactionContext, botId: String, timestamp: Long) {
        inWriteTransaction(transaction) {
            check(AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[lastUsedAt] = timestamp
                it[updatedAt] = timestamp
            } == 1) { "Locked bot disappeared during delivery admission" }
        }
    }

    override fun grant(transaction: PgTransactionContext, botId: String, chatId: String): Boolean =
        inWriteTransaction(transaction) {
            AutomationBotGrants.insertIgnore {
                it[AutomationBotGrants.botId] = botId
                it[AutomationBotGrants.chatId] = chatId
                it[createdAt] = System.currentTimeMillis()
            }.insertedCount == 1
        }

    override fun revokeGrant(transaction: PgTransactionContext, botId: String, chatId: String): Boolean =
        inWriteTransaction(transaction) {
            AutomationBotGrants.deleteWhere {
                (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
            } > 0
        }

    override fun listGrants(botId: String): List<String> = transaction { listGrantsInternal(botId) }

    override fun isGranted(botId: String, chatId: String): Boolean = transaction {
        AutomationBotGrants.selectAll().where {
            (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
        }.count() > 0
    }

    override fun forChat(chatId: String): Set<String> = transaction {
        AutomationBotGrants.join(
            otherTable = AutomationBots,
            joinType = JoinType.INNER,
            onColumn = AutomationBotGrants.botId,
            otherColumn = AutomationBots.botId,
        ).selectAll().where {
            (AutomationBotGrants.chatId eq chatId) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.mapTo(linkedSetOf()) { it[AutomationBots.userUid] }
    }

    override fun deactivateForChat(transaction: PgTransactionContext, chatId: String) {
        inWriteTransaction(transaction) {
            // ChatService already holds the chat row. Identity users, bot rows and grants are then
            // locked in that order so every application process shares one aggregate lock order.
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
        context: PgTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedTransaction()
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
