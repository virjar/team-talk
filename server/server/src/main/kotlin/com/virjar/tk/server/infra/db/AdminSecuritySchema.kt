package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

/** Single-instance administrator authority; application users have a separate credential model. */
internal object AdminSecurityCredentials : Table("admin_security_credentials") {
    val singleton = integer("singleton").check { it eq 1 }
    val username = varchar("username", 100)
    val passwordHash = varchar("password_hash", 100)
    val updatedAt = long("updated_at")
    val recoveryId = varchar("recovery_id", 36).nullable()
    override val primaryKey = PrimaryKey(singleton)
}

internal object AdminSecurityAudits : Table("admin_security_audits") {
    val id = long("id").autoIncrement()
    val actor = varchar("actor", 100)
    val action = varchar("action", 80)
    val target = varchar("target", 400)
    val createdAt = long("created_at")
    val completedAt = long("completed_at").nullable()
    val result = varchar("result", 16)
    val httpStatus = integer("http_status").nullable()
    val failureReason = varchar("failure_reason", 32).nullable()
    override val primaryKey = PrimaryKey(id)
}
