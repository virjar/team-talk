package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * 每个已认证 Android 安装一条合并唤醒记录；refresh 保留此 FK。
 * vendor 是设备厂商通道标识（xiaomi/huawei/honor/oppo/vivo/meizu），由客户端按制造商注册。
 */
internal object OemPushRegistrations : Table("oem_push_registrations") {
    val refreshTokenHash = varchar("refresh_token_hash", 64)
        .references(Credentials.tokenHash, onDelete = ReferenceOption.CASCADE)
    val vendor = varchar("vendor", 16)
    val registrationId = varchar("registration_id", 4096)
    val registrationHash = varchar("registration_hash", 64).uniqueIndex()
    val generation = varchar("generation", 36)
    val packageName = varchar("package_name", 255)
    val deploymentFingerprint = varchar("deployment_fingerprint", 64)
    val pendingEventId = long("pending_event_id").default(0)
    val deliveredEventId = long("delivered_event_id").default(0)
    /** chatId -> latest incoming message sequence; bounded by the existing account conversation quota. */
    val pendingChats = text("pending_chats").default("{}")
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = long("next_attempt_at").default(0)
    val lastFailure = varchar("last_failure", 40).nullable()
    override val primaryKey = PrimaryKey(refreshTokenHash)

    init {
        index("idx_oem_push_due", false, nextAttemptAt)
    }
}
