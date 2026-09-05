package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.attachment.UserAvatarReferences
import com.virjar.tk.server.infra.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/** PostgreSQL 当前档案对附件引用图的贡献。 */
class ExposedUserAvatarReferences(
    private val database: Database,
) : UserAvatarReferences {
    override fun getReferencedPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return transaction(database) {
            buildSet {
                paths.sorted().chunked(USER_AVATAR_REFERENCE_BATCH_SIZE).forEach { batch ->
                    Users.select(Users.avatarPath)
                        .where { Users.avatarPath inList batch }
                        .orderBy(Users.avatarPath to SortOrder.ASC)
                        .forEach { row -> row[Users.avatarPath]?.let(::add) }
                }
            }
        }
    }

    private companion object {
        const val USER_AVATAR_REFERENCE_BATCH_SIZE = 1_000
    }
}
