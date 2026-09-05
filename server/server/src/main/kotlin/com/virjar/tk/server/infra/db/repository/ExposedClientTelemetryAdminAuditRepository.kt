package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.telemetry.ClientTelemetryAdminAuditRepository
import com.virjar.tk.server.domain.telemetry.TelemetryAdminAuditEntry
import com.virjar.tk.server.infra.db.ClientTelemetryAdminAudits
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedClientTelemetryAdminAuditRepository(
    private val database: Database,
) : ClientTelemetryAdminAuditRepository {
    override fun append(entry: TelemetryAdminAuditEntry) {
        require(entry.actor.length in 1..MAX_ACTOR_CHARS && entry.actor.none(Char::isISOControl)) {
            "invalid telemetry audit actor"
        }
        require(entry.target.length in 1..MAX_TARGET_CHARS && entry.target.none(Char::isISOControl)) {
            "invalid telemetry audit target"
        }
        require(entry.occurredAt > 0L) { "invalid telemetry audit time" }
        transaction(database) {
            ClientTelemetryAdminAudits.insert {
                it[actor] = entry.actor
                it[action] = entry.action.name
                it[target] = entry.target
                it[result] = entry.result.name
                it[createdAt] = entry.occurredAt
            }
        }
    }

    private companion object {
        const val MAX_ACTOR_CHARS = 100
        const val MAX_TARGET_CHARS = 180
    }
}
