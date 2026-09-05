package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.application.admin.AdminUserDirectory
import com.virjar.tk.server.application.admin.AdminPage
import com.virjar.tk.server.application.admin.AdminPageRequest
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.toUserAvatar
import com.virjar.tk.protocol.model.User
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction

/** 仅限管理员的全局用户目录的 PostgreSQL 适配器。 */
class ExposedAdminUserDirectory(
    private val database: Database,
) : AdminUserDirectory {
    override fun listUsers(query: String?, pagination: AdminPageRequest): AdminPage<User> = transaction(database) {
        val base = Users.selectAll()
        val filtered = if (query.isNullOrBlank()) {
            base
        } else {
            base.where {
                (Users.username like "%$query%") or
                    (Users.name like "%$query%") or
                    (Users.uid like "%$query%")
            }
        }
        val total = filtered.count()
        val items = filtered
            .orderBy(Users.createdAt to SortOrder.DESC, Users.uid to SortOrder.ASC)
            .limit(pagination.size)
            .offset(pagination.offset)
            .map(ResultRow::toAdminUser)
        AdminPage(total, items)
    }

    override fun countUsers(): Long = transaction(database) { Users.selectAll().count() }
}

private fun ResultRow.toAdminUser() = User(
    uid = this[Users.uid],
    username = this[Users.username],
    name = this[Users.name],
    avatar = toUserAvatar(),
    phone = this[Users.phone],
    sex = this[Users.sex],
    role = this[Users.role],
    status = this[Users.status],
    revision = this[Users.revision],
)
