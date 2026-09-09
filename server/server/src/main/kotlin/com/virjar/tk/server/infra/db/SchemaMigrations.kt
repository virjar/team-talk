package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/** Ordered, data-preserving changes within the existing storage epoch; version numbering starts at 0. */
internal object SchemaMigrations : Table("schema_migrations") {
    val version = integer("version")
    val name = varchar("name", 100)
    val appliedAt = long("applied_at")

    override val primaryKey = PrimaryKey(version)
}

private class SchemaMigration(val name: String, val apply: Transaction.() -> Unit)

// Append only. Retain the order, name and SQL of already released migrations.
private val schemaMigrations = listOf(
    SchemaMigration("expand_client_telemetry_protocol_id") {
        // PostgreSQL INTEGER already has Int.MAX_VALUE as its upper bound. Keep rejecting negatives.
        // Dropping a missing constraint deliberately fails: this migration only supports the known layout.
        exec("ALTER TABLE client_telemetry_devices DROP CONSTRAINT ck_client_telemetry_device_protocol_version")
        exec(
            "ALTER TABLE client_telemetry_devices ADD CONSTRAINT ck_client_telemetry_device_protocol_version " +
                "CHECK (protocol_version >= 0)",
        )
    },
    SchemaMigration("create_banned_credential_tombstones") {
        // T013：封禁账号的 refresh token 摘要墓碑，支持把旧凭据重连权威判定为账号封禁。
        SchemaUtils.create(BannedCredentialTombstones)
    },
    SchemaMigration("create_admin_security") {
        SchemaUtils.create(AdminSecurityCredentials, AdminSecurityAudits)
    },
    SchemaMigration("create_document_comments") {
        SchemaUtils.create(DocumentComments)
    },
    SchemaMigration("create_content_search_pending") {
        SchemaUtils.create(ContentSearchPending)
    },
    SchemaMigration("create_tasks") {
        SchemaUtils.create(WorkTasks, TaskAudits, TaskCommands)
    },
    SchemaMigration("create_chat_drafts") {
        SchemaUtils.create(ChatDrafts, ChatDraftAssets, ChatDraftCommands)
    },
    SchemaMigration("create_xiaomi_push_registrations") {
        SchemaUtils.create(XiaomiPushRegistrations)
    },
)

/** Caller owns the schema_metadata lock; DDL and its completion receipt commit in the same transaction. */
internal fun Transaction.applySchemaMigrations() {
    SchemaUtils.create(SchemaMigrations)
    val applied = SchemaMigrations.selectAll().orderBy(SchemaMigrations.version)
        .limit(schemaMigrations.size + 1).toList()
    check(applied.size <= schemaMigrations.size) {
        "Database has newer schema migrations; preserve its data and use a compatible server"
    }
    applied.forEachIndexed { version, row ->
        check(row[SchemaMigrations.version] == version && row[SchemaMigrations.name] == schemaMigrations[version].name) {
            "Database schema migration history is inconsistent; preserve its data and inspect the migration ledger"
        }
    }
    for (version in applied.size until schemaMigrations.size) {
        val migration = schemaMigrations[version]
        migration.apply(this)
        SchemaMigrations.insert {
            it[SchemaMigrations.version] = version
            it[name] = migration.name
            it[appliedAt] = System.currentTimeMillis()
        }
    }
}
