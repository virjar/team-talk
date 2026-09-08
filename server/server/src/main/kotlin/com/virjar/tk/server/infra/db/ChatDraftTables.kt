package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

object ChatDrafts : Table("chat_drafts") {
    // Conversation writes lock Chat before the per-user ledger; a User FK would invert auth's User -> Chat order.
    val uid = varchar("uid", 36)
    val chatId = varchar("chat_id", 36).references(Chats.chatId)
    val revision = long("revision")
    val updatedAt = long("updated_at")
    val payload = binary("payload").nullable()
    val payloadSize = integer("payload_size")
    override val primaryKey = PrimaryKey(uid, chatId)
    init { check("ck_chat_draft_revision") { revision greater 0L } }
}

/** Private account references, deliberately excluded from chat-member attachment ACLs. */
object ChatDraftAssets : Table("chat_draft_assets") {
    val uid = varchar("uid", 36)
    val chatId = varchar("chat_id", 36).references(Chats.chatId)
    val path = varchar("path", 500)
    override val primaryKey = PrimaryKey(uid, chatId, path)
    init { index("idx_chat_draft_asset_path", false, path) }
}

object ChatDraftCommands : Table("chat_draft_commands") {
    val uid = varchar("uid", 36)
    val operationId = varchar("operation_id", 36)
    val fingerprint = varchar("fingerprint", 64)
    val applied = bool("applied")
    val revision = long("revision")
    val expiresAt = long("expires_at").index("idx_chat_draft_command_expiry")
    override val primaryKey = PrimaryKey(uid, operationId)
}
