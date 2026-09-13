package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.attachment.GroupAvatarReferences
import com.virjar.tk.server.infra.db.GroupChats
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/** PostgreSQL group_chats 头像列对附件引用图与成员读取的贡献（内测反馈 T053）。 */
class ExposedGroupAvatarReferences(
    private val database: Database,
) : GroupAvatarReferences {
    override fun getReferencedPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return transaction(database) {
            buildSet {
                paths.sorted().chunked(GROUP_AVATAR_REFERENCE_BATCH_SIZE).forEach { batch ->
                    GroupChats.select(GroupChats.avatarPath)
                        .where { GroupChats.avatarPath inList batch }
                        .orderBy(GroupChats.avatarPath, SortOrder.ASC)
                        .forEach { row -> row[GroupChats.avatarPath]?.let(::add) }
                }
            }
        }
    }

    override fun getCurrentAvatarChatId(path: String): String? = transaction(database) {
        GroupChats.select(GroupChats.chatId)
            .where { GroupChats.avatarPath eq path }
            .orderBy(GroupChats.chatId, SortOrder.ASC)
            .limit(1)
            .firstOrNull()
            ?.get(GroupChats.chatId)
    }

    private companion object {
        const val GROUP_AVATAR_REFERENCE_BATCH_SIZE = 1_000
    }
}
