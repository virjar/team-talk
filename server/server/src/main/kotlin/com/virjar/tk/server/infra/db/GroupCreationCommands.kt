package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

/** 一个客户端拥有的用户建群操作的不可变回执。 */
object GroupCreationCommands : Table("group_creation_commands") {
    val creatorUid = varchar("creator_uid", 36).references(Users.uid)
    val operationId = varchar("operation_id", 36)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val chatId = varchar("chat_id", 36).references(Chats.chatId).uniqueIndex()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(creatorUid, operationId)
}
