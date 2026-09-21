package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** Ordered, data-preserving changes within the existing storage epoch; version numbering starts at 0. */
internal object SchemaMigrations : Table("schema_migrations") {
    val version = integer("version")
    val name = varchar("name", 100)
    val appliedAt = long("applied_at")

    override val primaryKey = PrimaryKey(version)
}

private class SchemaMigration(val name: String, val apply: Transaction.() -> Unit)

// Append only. Retain the order, name and SQL of already released migrations.
// v0.0.2 开发期的全部表结构增量按发布批次收敛为一条：未发行中间态（含小米单厂商表）不再保留账目。
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
    SchemaMigration("create_v0_0_2_tables") {
        SchemaUtils.create(
            AdminSecurityCredentials, AdminSecurityAudits,
            DocumentComments,
            ContentSearchPending,
            WorkTasks, TaskAudits, TaskCommands,
            ChatDrafts, ChatDraftAssets, ChatDraftCommands,
            OemPushRegistrations,
            AdminFeatureSettings,
        )
        exec("ALTER TABLE conversations ADD COLUMN IF NOT EXISTS mentioned boolean NOT NULL DEFAULT FALSE")
    },
    SchemaMigration("add_group_avatar_columns") {
        // T053：群头像 canonical 描述符四列，全有或全无；既有行为不受影响。
        exec("ALTER TABLE group_chats ADD COLUMN IF NOT EXISTS avatar_path varchar(500)")
        exec("ALTER TABLE group_chats ADD COLUMN IF NOT EXISTS avatar_name varchar(200)")
        exec("ALTER TABLE group_chats ADD COLUMN IF NOT EXISTS avatar_content_type varchar(100)")
        exec("ALTER TABLE group_chats ADD COLUMN IF NOT EXISTS avatar_size bigint")
    },
    SchemaMigration("create_client_release_registry") {
        // 统一客户端发布与更新体系：发布注册中心三张表。
        SchemaUtils.create(ClientReleases, ClientReleaseFiles, ClientChannels)
    },
    SchemaMigration("client_release_build_identity") {
        exec("ALTER TABLE client_release ADD COLUMN IF NOT EXISTS build_identity varchar(128) NOT NULL DEFAULT ''")
        exec("ALTER TABLE client_release ADD COLUMN IF NOT EXISTS upload_sha256 varchar(64)")
        exec("ALTER TABLE client_release DROP CONSTRAINT IF EXISTS uq_client_release_identity")
        exec("DROP INDEX IF EXISTS uq_client_release_identity")
        exec("CREATE UNIQUE INDEX uq_client_release_identity ON client_release (client_type, platform, arch, version, build, build_identity)")
    },
    SchemaMigration("remove_fixed_system_conversation_projections") {
        // 固定系统身份只参与聊天和消息，不拥有客户端列表。只清理这两名所有者的派生行，
        // 保留人类 Conversation、成员、消息、附件和既有同步记录；与迁移收据原子提交。
        exec("DELETE FROM conversations WHERE uid IN ('sys_assistant', 'sys_service')")
        exec("DELETE FROM conversation_usages WHERE uid IN ('sys_assistant', 'sys_service')")
    },
    SchemaMigration("create_task_details_and_weekly_series") {
        // Missing extension rows deliberately mean private/plain-text/unknown historical metrics.
        // SchemaUtils.create commits internally; these DDL statements must share the receipt transaction.
        SchemaUtils.createStatements(TaskExtensions, TaskAttachmentPaths, TaskDeferrals, TaskSeriesTemplates, TaskSeriesAttachmentPaths)
            .forEach { exec(it) }
    },
    SchemaMigration("add_user_pinyin_search_columns") {
        // T062：姓名拼音搜索键两列 + 存量全量回填。派生是纯函数（TinyPinyin 常见读音），
        // 回填与账目收据同事务提交；空串列是幂等回填的游标。
        exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS name_pinyin_full varchar(300) NOT NULL DEFAULT ''")
        exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS name_pinyin_initials varchar(100) NOT NULL DEFAULT ''")
        val pending = Users.selectAll()
            .where { Users.namePinyinFull eq "" }
            .map { it[Users.uid] to it[Users.name] }
        pending.forEach { (uid, name) ->
            Users.update({ Users.uid eq uid }) {
                it[Users.namePinyinFull] = derivePinyinFull(name)
                it[Users.namePinyinInitials] = derivePinyinInitials(name)
            }
        }
    },
    SchemaMigration("create_service_account_tables") {
        // 服务号官方触达：欢迎语模板 + 管理员全员广播台账（T058 扩展需求）。
        SchemaUtils.createStatements(ServiceContents, ServiceBroadcasts).forEach { exec(it) }
    },
    SchemaMigration("add_push_registration_timestamp") {
        exec("ALTER TABLE oem_push_registrations ADD COLUMN IF NOT EXISTS registered_at bigint NOT NULL DEFAULT 0")
    },
)

/** Caller owns the schema_metadata lock; new migrations must not commit before their completion receipt. */
internal fun Transaction.applySchemaMigrations() {
    // Preserve the caller's schema_metadata lock, even when the ledger already exists.
    SchemaUtils.createStatements(SchemaMigrations).forEach { exec(it) }
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
