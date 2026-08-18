package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.bot.AutomationBot
import com.virjar.tk.domain.bot.BotRepository
import com.virjar.tk.infra.db.AutomationBotGrants
import com.virjar.tk.infra.db.AutomationBots
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedBotRepository : BotRepository {
    override fun create(bot: AutomationBot, tokenHash: String): AutomationBot = transaction {
        AutomationBots.insert {
            it[botId] = bot.botId
            it[userUid] = bot.userUid
            it[name] = bot.name
            it[AutomationBots.tokenHash] = tokenHash
            it[status] = bot.status
            it[createdAt] = bot.createdAt
            it[updatedAt] = bot.createdAt
        }
        bot
    }

    override fun list(): List<AutomationBot> = transaction {
        AutomationBots.selectAll().orderBy(AutomationBots.createdAt to SortOrder.DESC)
            .map { it.toBot(listGrantsInternal(it[AutomationBots.botId])) }
    }

    override fun find(botId: String): AutomationBot? = transaction {
        AutomationBots.selectAll().where { AutomationBots.botId eq botId }.singleOrNull()
            ?.toBot(listGrantsInternal(botId))
    }

    override fun findByTokenHash(tokenHash: String): AutomationBot? = transaction {
        val row = AutomationBots.selectAll().where { AutomationBots.tokenHash eq tokenHash }.singleOrNull()
            ?: return@transaction null
        row.toBot(listGrantsInternal(row[AutomationBots.botId]))
    }

    override fun updateTokenHash(botId: String, tokenHash: String) {
        transaction {
            AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[AutomationBots.tokenHash] = tokenHash
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override fun setStatus(botId: String, status: Int) {
        transaction {
            AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[AutomationBots.status] = status
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override fun touch(botId: String, timestamp: Long) {
        transaction {
            AutomationBots.update({ AutomationBots.botId eq botId }) {
                it[lastUsedAt] = timestamp
                it[updatedAt] = timestamp
            }
        }
    }

    override fun grant(botId: String, chatId: String) {
        transaction {
            AutomationBotGrants.insertIgnore {
                it[AutomationBotGrants.botId] = botId
                it[AutomationBotGrants.chatId] = chatId
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    override fun revokeGrant(botId: String, chatId: String) {
        transaction {
            AutomationBotGrants.deleteWhere {
                (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
            }
        }
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

    private fun listGrantsInternal(botId: String): List<String> =
        AutomationBotGrants.selectAll().where { AutomationBotGrants.botId eq botId }
            .map { it[AutomationBotGrants.chatId] }
}

private fun ResultRow.toBot(grants: List<String>) = AutomationBot(
    botId = this[AutomationBots.botId],
    userUid = this[AutomationBots.userUid],
    name = this[AutomationBots.name],
    status = this[AutomationBots.status],
    grantedChatIds = grants,
    lastUsedAt = this[AutomationBots.lastUsedAt],
    createdAt = this[AutomationBots.createdAt],
)
