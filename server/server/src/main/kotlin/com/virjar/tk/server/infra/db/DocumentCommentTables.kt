package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

object DocumentComments : Table("document_comments") {
    val commentId = varchar("comment_id", 36)
    val spaceId = varchar("space_id", 36)
    val documentId = varchar("document_id", 36)
    val sequence = long("sequence").autoIncrement().uniqueIndex()
    val authorUid = varchar("author_uid", 36)
    val authorName = varchar("author_name", 128)
    val replyToId = varchar("reply_to_id", 36).nullable()
    val body = text("body")
    val revision = long("revision")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val deleted = bool("deleted").default(false)
    val creationFingerprint = varchar("creation_fingerprint", 64)
    override val primaryKey = PrimaryKey(commentId)
    init { index("idx_document_comments_page", false, spaceId, documentId, sequence) }
}
