package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.application.admin.AdminChatDirectory
import com.virjar.tk.server.application.admin.AdminPage
import com.virjar.tk.server.application.admin.AdminPageRequest
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.protocol.model.Chat
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction

/** 仅限管理员的全局聊天读取与概览计数器的 PostgreSQL 适配器。 */
class ExposedAdminChatDirectory(
    private val database: Database,
) : AdminChatDirectory {
    override fun listGroups(query: String?, pagination: AdminPageRequest): AdminPage<Chat> =
        transaction(database) {
            val condition = if (query.isNullOrBlank()) {
                Op.TRUE and (Chats.chatType eq 2)
            } else {
                (Chats.chatType eq 2) and (GroupChats.name like "%$query%")
            }
            val filtered = (Chats innerJoin GroupChats).selectAll().where { condition }
            val total = filtered.count()
            val rows = filtered
                .orderBy(Chats.createdAt to SortOrder.DESC, Chats.chatId to SortOrder.ASC)
                .limit(pagination.size)
                .offset(pagination.offset)
                .toList()
            val memberCounts = activeMemberCounts(rows.map { it[Chats.chatId] })
            val items = rows.map { row ->
                val chatId = row[Chats.chatId]
                row.toAdminGroup(memberCounts[chatId] ?: 0)
            }
            AdminPage(total, items)
        }

    override fun findGroup(chatId: String): Chat? = transaction(database) {
        val row = (Chats innerJoin GroupChats).selectAll().where {
            (Chats.chatId eq chatId) and (Chats.chatType eq 2)
        }.singleOrNull() ?: return@transaction null
        row.toAdminGroup(activeMemberCounts(listOf(chatId))[chatId] ?: 0)
    }

    override fun countGroups(): Long = transaction(database) {
        Chats.selectAll().where { Chats.chatType eq 2 }.count()
    }

    override fun countEventsSince(sinceMillis: Long): Long = transaction(database) {
        SyncEvents.selectAll().where { SyncEvents.createdAt greater sinceMillis }.count()
    }

    private fun activeMemberCounts(chatIds: List<String>): Map<String, Int> {
        if (chatIds.isEmpty()) return emptyMap()
        val memberCount = GroupMembers.id.count()
        return GroupMembers.select(GroupMembers.chatId, memberCount)
            .where { (GroupMembers.chatId inList chatIds.distinct()) and (GroupMembers.status eq 1) }
            .groupBy(GroupMembers.chatId)
            .associate { row ->
                row[GroupMembers.chatId] to row[memberCount].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
    }
}

private fun ResultRow.toAdminGroup(memberCount: Int): Chat =
    Chat(
        chatId = this[Chats.chatId],
        chatType = 2,
        name = this[GroupChats.name],
        avatar = this[GroupChats.avatar],
        creator = this[GroupChats.creator],
        memberCount = memberCount,
        maxSeq = this[Chats.maxSeq],
        notice = this[GroupChats.notice],
        mutedAll = this[GroupChats.mutedAll],
    )
