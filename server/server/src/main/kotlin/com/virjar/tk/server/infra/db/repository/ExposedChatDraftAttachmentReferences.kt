package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.attachment.ChatDraftAttachmentReferences
import com.virjar.tk.server.infra.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedChatDraftAttachmentReferences(private val database: Database) : ChatDraftAttachmentReferences {
    override fun getReferencedPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return transaction(database) {
            ChatDraftAssets.select(ChatDraftAssets.path).where { ChatDraftAssets.path inList paths }
                .mapTo(linkedSetOf()) { it[ChatDraftAssets.path] }
        }
    }

    override fun canRead(uid: String, path: String): Boolean = transaction(database) {
        ChatDraftAssets.join(Chats, JoinType.INNER, ChatDraftAssets.chatId, Chats.chatId)
            .join(GroupMembers, JoinType.INNER, ChatDraftAssets.chatId, GroupMembers.chatId)
            .join(OrganizationManagedChatProjections, JoinType.LEFT, Chats.chatId, OrganizationManagedChatProjections.chatId)
            .select(ChatDraftAssets.path).where {
                (ChatDraftAssets.uid eq uid) and (ChatDraftAssets.path eq path) and (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1) and (Chats.status eq 1) and
                    (OrganizationManagedChatProjections.chatId.isNull() or (
                        (OrganizationManagedChatProjections.desiredActive eq true) and
                            (OrganizationManagedChatProjections.desiredRevision eq OrganizationManagedChatProjections.appliedRevision) and
                            OrganizationManagedChatProjections.lastFailure.isNull()))
            }.limit(1).any()
    }
}
