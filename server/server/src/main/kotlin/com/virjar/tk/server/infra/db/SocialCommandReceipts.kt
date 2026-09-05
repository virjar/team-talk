package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and

/** 接受或拒绝好友请求的时间有界重放回执。 */
object ContactDecisionReceipts : LongIdTable("contact_decision_receipts") {
    // 刻意不做外键：预留操作身份绝不能在联系人仓库全局排序的
    // User 对锁顺序之前获取 User key-share 锁。
    val actorUid = varchar("actor_uid", 36)
    val operationId = varchar("operation_id", 36)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val decision = integer("decision")
    /** 仅在预留了这个唯一操作身份的事务内部为 null。 */
    val resultPayload = binary("result_payload").nullable()
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")

    init {
        uniqueIndex("uq_contact_decision_actor_operation", actorUid, operationId)
        index("idx_contact_decision_expiry", false, expiresAt, id)
        check("ck_contact_decision_type") { decision inList listOf(1, 2) }
        check("ck_contact_decision_time") {
            (issuedAt greaterEq 0L) and (expiresAt greaterEq issuedAt) and (createdAt greaterEq 0L)
        }
    }
}

/** 与邀请链接的活跃生命周期相互独立保存的时间有界重放回执。 */
object InviteLinkCreationReceipts : LongIdTable("invite_link_creation_receipts") {
    // 邀请写入器只有在当前授权、Chat、User 和 Member 锁
    // 都准入该 actor 之后才预留此行；因此重放授权先于读取秘密 token。
    val actorUid = varchar("actor_uid", 36)
    val operationId = varchar("operation_id", 36)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val chatId = varchar("chat_id", 36)
    /** 仅在唯一操作于其创建事务内部被预留期间为 null。 */
    val token = varchar("token", 36).nullable()
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")

    init {
        uniqueIndex("uq_invite_creation_actor_operation", actorUid, operationId)
        index("idx_invite_creation_expiry", false, expiresAt, id)
        check("ck_invite_creation_time") {
            (issuedAt greaterEq 0L) and (expiresAt greaterEq issuedAt) and (createdAt greaterEq 0L)
        }
    }
}
