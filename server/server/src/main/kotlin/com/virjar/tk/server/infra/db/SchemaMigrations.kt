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

/** 历史账目 create_xiaomi_push_registrations 引用的原始表定义；现行表对象为 [OemPushRegistrations]。 */
private object XiaomiPushRegistrations : Table("xiaomi_push_registrations") {
    val refreshTokenHash = varchar("refresh_token_hash", 64)
    val registrationId = varchar("registration_id", 4096)
    val registrationHash = varchar("registration_hash", 64).uniqueIndex()
    val generation = varchar("generation", 36)
    val packageName = varchar("package_name", 255)
    val deploymentFingerprint = varchar("deployment_fingerprint", 64)
    val pendingEventId = long("pending_event_id").default(0)
    val deliveredEventId = long("delivered_event_id").default(0)
    val pendingChats = text("pending_chats").default("{}")
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = long("next_attempt_at").default(0)
    val lastFailure = varchar("last_failure", 40).nullable()
    override val primaryKey = PrimaryKey(refreshTokenHash)

    init {
        index("idx_xiaomi_push_due", false, nextAttemptAt)
    }
}

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
    SchemaMigration("generalize_oem_push_registrations") {
        // 单一小米通道扩展为多厂商注册表。全新库已按最终布局建表且账目回放会重建空的小米旧表，
        // 此时直接丢弃回放产物；历史库则保留数据改名。
        exec(
            """
            DO $$ BEGIN
                IF to_regclass('xiaomi_push_registrations') IS NOT NULL THEN
                    IF to_regclass('oem_push_registrations') IS NOT NULL THEN
                        DROP TABLE xiaomi_push_registrations;
                    ELSE
                        ALTER TABLE xiaomi_push_registrations RENAME TO oem_push_registrations;
                    END IF;
                END IF;
            END $$;
            """.trimIndent(),
        )
        exec("ALTER TABLE oem_push_registrations ADD COLUMN IF NOT EXISTS vendor varchar(16) NOT NULL DEFAULT 'xiaomi'")
    },
    SchemaMigration("create_admin_feature_settings") {
        SchemaUtils.create(AdminFeatureSettings)
    },
    SchemaMigration("add_conversations_mentioned") {
        exec("ALTER TABLE conversations ADD COLUMN IF NOT EXISTS mentioned boolean NOT NULL DEFAULT FALSE")
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
