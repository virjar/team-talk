package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * 群机器人凭据命令的持久回执。
 *
 * 可恢复的 webhook token 绝不会进入这张表。[tokenHash] 将一次重放绑定到确切的
 * 客户端持有 token，并允许服务拒绝被后续轮换所取代的回执。
 */
object BotCredentialCommands : Table("bot_credential_commands") {
    val actorUid = reference("actor_uid", Users.uid, onDelete = ReferenceOption.CASCADE)
    val operationId = varchar("operation_id", 36)
    val commandKind = integer("command_kind")
    val chatId = varchar("chat_id", 36)
    val botId = reference("bot_id", AutomationBots.botId, onDelete = ReferenceOption.CASCADE).index()
    val botUserUid = reference("bot_user_uid", Users.uid, onDelete = ReferenceOption.CASCADE)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val tokenHash = varchar("token_hash", 64)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(actorUid, operationId)

    init {
        check("ck_bot_credential_command_kind") { commandKind inList listOf(1, 2) }
        check("ck_bot_credential_command_created_non_negative") { createdAt greaterEq 0L }
    }
}
