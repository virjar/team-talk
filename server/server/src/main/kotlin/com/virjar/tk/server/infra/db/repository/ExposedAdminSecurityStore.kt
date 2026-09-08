package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.application.admin.AdminAuditRecord
import com.virjar.tk.server.application.admin.AdminAuditFailureReason
import com.virjar.tk.server.application.admin.AdminCredential
import com.virjar.tk.server.application.admin.AdminSecurityStore
import com.virjar.tk.server.infra.db.AdminSecurityAudits
import com.virjar.tk.server.infra.db.AdminSecurityCredentials
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedAdminSecurityStore(private val database: Database) : AdminSecurityStore {
    override fun credential(): AdminCredential? = transaction(database) {
        AdminSecurityCredentials.selectAll().singleOrNull()?.let {
            AdminCredential(it[AdminSecurityCredentials.username], it[AdminSecurityCredentials.passwordHash],
                it[AdminSecurityCredentials.updatedAt], it[AdminSecurityCredentials.recoveryId])
        }
    }

    override fun saveCredential(credential: AdminCredential, action: String, now: Long) = transaction(database) {
        // The application owns one administrator service. The credential and its audit commit together.
        AdminSecurityCredentials.deleteWhere { singleton eq 1 }
        AdminSecurityCredentials.insert {
            it[singleton] = 1
            it[username] = credential.username
            it[passwordHash] = credential.passwordHash
            it[updatedAt] = credential.updatedAt
            it[recoveryId] = credential.recoveryId
        }
        appendAudit(credential.username, action, "administrator", now, "SUCCESS", 200)
        Unit
    }

    override fun beginAudit(actor: String, action: String, target: String, now: Long): Long = transaction(database) {
        appendAudit(actor, action, target, now, "STARTED", null)
    }

    override fun completeAudit(id: Long, status: Int, now: Long, failureReason: AdminAuditFailureReason?) = transaction(database) {
        AdminSecurityAudits.update({ AdminSecurityAudits.id eq id }) {
            it[completedAt] = now
            it[httpStatus] = status
            it[result] = when (status) { in 200..399 -> "SUCCESS"; in 400..499 -> "REJECTED"; else -> "FAILED" }
            it[AdminSecurityAudits.failureReason] = failureReason?.name
        }
        Unit
    }

    override fun audits(beforeId: Long?, limit: Int): List<AdminAuditRecord> = transaction(database) {
        require(limit in 1..100 && (beforeId == null || beforeId > 0))
        val query = AdminSecurityAudits.selectAll()
        if (beforeId != null) query.where { AdminSecurityAudits.id less beforeId }
        query.orderBy(AdminSecurityAudits.id, SortOrder.DESC).limit(limit).map {
            AdminAuditRecord(it[AdminSecurityAudits.id], it[AdminSecurityAudits.actor],
                it[AdminSecurityAudits.action], it[AdminSecurityAudits.target], it[AdminSecurityAudits.createdAt],
                it[AdminSecurityAudits.completedAt], it[AdminSecurityAudits.result], it[AdminSecurityAudits.httpStatus],
                it[AdminSecurityAudits.failureReason]?.let(AdminAuditFailureReason::valueOf))
        }
    }

    private fun Transaction.appendAudit(
        actor: String, action: String, target: String, now: Long, result: String, status: Int?,
    ): Long {
        require(actor.length in 1..100 && actor.none(Char::isISOControl))
        require(action.length in 1..80 && action.none(Char::isISOControl))
        require(target.length in 1..400 && target.none(Char::isISOControl))
        val id = AdminSecurityAudits.insert {
            it[AdminSecurityAudits.actor] = actor
            it[AdminSecurityAudits.action] = action
            it[AdminSecurityAudits.target] = target
            it[createdAt] = now
            it[completedAt] = if (status == null) null else now
            it[AdminSecurityAudits.result] = result
            it[httpStatus] = status
        }[AdminSecurityAudits.id]
        // Monotonic ids provide a bounded, keyset-paged local history, including interrupted attempts.
        AdminSecurityAudits.deleteWhere { AdminSecurityAudits.id lessEq (id - MAX_AUDIT_RECORDS) }
        return id
    }

    private companion object { const val MAX_AUDIT_RECORDS = 10_000L }
}
