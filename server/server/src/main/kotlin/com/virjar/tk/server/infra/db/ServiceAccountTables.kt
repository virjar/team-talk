package com.virjar.tk.server.infra.db

import com.virjar.tk.server.domain.message.ServiceAccountDirectory
import com.virjar.tk.server.domain.message.ServiceBroadcastEntry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 服务号内容模板（当前仅欢迎语）；键控单行，覆盖式更新。 */
internal object ServiceContents : Table("service_contents") {
    const val KEY_WELCOME_TEMPLATE = "welcome_template"

    val settingKey = varchar("setting_key", 64)
    val content = text("content")
    val updatedAt = long("updated_at")
    val updatedBy = varchar("updated_by", 64)

    override val primaryKey = PrimaryKey(settingKey)
}

/** 管理员经服务号向全员推送的广播台账；计数为最近一次执行的结果。 */
internal object ServiceBroadcasts : Table("service_broadcasts") {
    val broadcastId = varchar("broadcast_id", 64)
    val markdown = text("markdown")
    val createdBy = varchar("created_by", 64)
    val createdAt = long("created_at")
    val finishedAt = long("finished_at").nullable()
    val totalUsers = integer("total_users").default(0)
    val coveredUsers = integer("covered_users").default(0)
    val failedUsers = integer("failed_users").default(0)
    val lastError = text("last_error").nullable()

    override val primaryKey = PrimaryKey(broadcastId)
}

internal class ServiceAccountStore(private val database: Database) : ServiceAccountDirectory {
    override fun getWelcomeTemplate(): String? = transaction(database) {
        ServiceContents.selectAll()
            .where { ServiceContents.settingKey eq ServiceContents.KEY_WELCOME_TEMPLATE }
            .singleOrNull()?.get(ServiceContents.content)
    }

    override fun setWelcomeTemplate(content: String, updatedBy: String) {
        transaction(database) {
            // 覆盖式更新模板；模板变更只影响之后首次收到欢迎语的用户，不重发历史欢迎语。
            ServiceContents.upsert(ServiceContents.settingKey) {
                it[settingKey] = ServiceContents.KEY_WELCOME_TEMPLATE
                it[ServiceContents.content] = content
                it[updatedAt] = System.currentTimeMillis()
                it[ServiceContents.updatedBy] = updatedBy
            }
        }
    }

    override fun insertBroadcast(broadcastId: String, markdown: String, createdBy: String) = transaction(database) {
        ServiceBroadcasts.insert {
            it[ServiceBroadcasts.broadcastId] = broadcastId
            it[ServiceBroadcasts.markdown] = markdown
            it[ServiceBroadcasts.createdBy] = createdBy
            it[createdAt] = System.currentTimeMillis()
        }
        Unit
    }

    override fun getBroadcast(broadcastId: String): ServiceBroadcastEntry? = transaction(database) {
        ServiceBroadcasts.selectAll()
            .where { ServiceBroadcasts.broadcastId eq broadcastId }
            .singleOrNull()?.toRecord()
    }

    override fun listBroadcasts(limit: Int): List<ServiceBroadcastEntry> = transaction(database) {
        ServiceBroadcasts.selectAll()
            .orderBy(ServiceBroadcasts.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(limit)
            .map { it.toRecord() }
    }

    override fun listUnfinishedBroadcastIds(): List<String> = transaction(database) {
        ServiceBroadcasts.selectAll()
            .where { ServiceBroadcasts.finishedAt.isNull() }
            .orderBy(ServiceBroadcasts.createdAt)
            .map { it[ServiceBroadcasts.broadcastId] }
    }

    override fun markBroadcastTotal(broadcastId: String, totalUsers: Int) = transaction(database) {
        ServiceBroadcasts.update({ ServiceBroadcasts.broadcastId eq broadcastId }) {
            it[ServiceBroadcasts.totalUsers] = totalUsers
        }
        Unit
    }

    override fun recordBroadcastProgress(
        broadcastId: String,
        coveredUsers: Int,
        failedUsers: Int,
        lastError: String?,
    ) = transaction(database) {
        ServiceBroadcasts.update({ ServiceBroadcasts.broadcastId eq broadcastId }) {
            it[ServiceBroadcasts.coveredUsers] = coveredUsers
            it[ServiceBroadcasts.failedUsers] = failedUsers
            if (lastError != null) it[ServiceBroadcasts.lastError] = lastError
        }
        Unit
    }

    override fun finishBroadcast(broadcastId: String) = transaction(database) {
        ServiceBroadcasts.update({ ServiceBroadcasts.broadcastId eq broadcastId }) {
            it[finishedAt] = System.currentTimeMillis()
        }
        Unit
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRecord() = ServiceBroadcastEntry(
        broadcastId = this[ServiceBroadcasts.broadcastId],
        markdown = this[ServiceBroadcasts.markdown],
        createdBy = this[ServiceBroadcasts.createdBy],
        createdAt = this[ServiceBroadcasts.createdAt],
        finishedAt = this[ServiceBroadcasts.finishedAt],
        totalUsers = this[ServiceBroadcasts.totalUsers],
        coveredUsers = this[ServiceBroadcasts.coveredUsers],
        failedUsers = this[ServiceBroadcasts.failedUsers],
        lastError = this[ServiceBroadcasts.lastError],
    )
}

