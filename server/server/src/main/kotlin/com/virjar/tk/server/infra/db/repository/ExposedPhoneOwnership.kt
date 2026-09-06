package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.MainlandPhoneNumber
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.server.infra.db.requireExposedTransaction
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * 不改写历史资料。新占用同时检查裸号和旧 +86 号，并在同一事务内串行化该号码的申请。
 * 数据库原有唯一索引继续兜底；事务锁封住跨格式预读与注册/更新之间的并发窗口。
 */
internal fun PgWriteTransactionContext.requirePhoneAvailable(phone: String, ownerUid: String) {
    val canonical = requireNotNull(MainlandPhoneNumber.normalize(phone))
    requireExposedTransaction().execRawSql(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        listOf(TextColumnType() to "teamtalk:phone:$canonical"),
    )
    val occupied = Users.selectAll().where {
        (Users.phone inList listOf(canonical, "+86$canonical")) and (Users.uid neq ownerUid)
    }.limit(1).any()
    require(!occupied) { "手机号已被使用" }
}
