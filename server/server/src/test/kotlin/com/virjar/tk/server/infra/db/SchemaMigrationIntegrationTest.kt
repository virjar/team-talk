package com.virjar.tk.server.infra.db

import com.virjar.tk.server.testing.PostgresSchemaLease
import java.sql.Connection
import java.sql.SQLException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaMigrationIntegrationTest {
    @Test
    fun `task extension migration preserves existing tasks and dataset across failure and reopen`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.execute("DROP TABLE task_extensions, task_attachment_paths, task_deferrals, task_series_attachment_paths, task_series")
                statement.execute("DELETE FROM schema_migrations WHERE version >= 7")
                statement.execute("INSERT INTO users (uid, username, name, password_hash, created_at, updated_at) " +
                    "VALUES ('legacy-task-owner', 'legacy-task-owner', 'kept', 'fixture-only', 11, 12)")
                statement.execute("INSERT INTO work_tasks (task_id, creator_uid, assignee_uid, title, description, status, context_kind, context_id, due_at, revision, created_at, updated_at) " +
                    "VALUES ('00000000-0000-4000-8000-000000000007', 'legacy-task-owner', 'legacy-task-owner', '旧标题', '*旧纯文本*', 1, 0, '', 100, 5, 11, 12)")
                statement.execute("CREATE FUNCTION reject_task_extension_receipt() RETURNS trigger LANGUAGE plpgsql AS " +
                    "'BEGIN IF NEW.version = 7 THEN RAISE EXCEPTION ''fixture receipt failure''; END IF; RETURN NEW; END;'")
                statement.execute("CREATE TRIGGER reject_task_extension BEFORE INSERT ON schema_migrations FOR EACH ROW EXECUTE FUNCTION reject_task_extension_receipt()")
            } }
            assertFailsWith<Exception> { open(lease).close() }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('task_extensions') IS NULL").use { assertTrue(it.next()); assertTrue(it.getBoolean(1)) }
                statement.execute("DROP TRIGGER reject_task_extension ON schema_migrations")
                statement.execute("DROP FUNCTION reject_task_extension_receipt()")
            } }
            repeat(2) {
                open(lease).use { database -> assertEquals(datasetId, database.datasetId) }
                lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT title, description, due_at, revision FROM work_tasks WHERE creator_uid = 'legacy-task-owner'").use {
                        assertTrue(it.next()); assertEquals("旧标题", it.getString(1)); assertEquals("*旧纯文本*", it.getString(2)); assertEquals(100L, it.getLong(3)); assertEquals(5L, it.getLong(4))
                    }
                    statement.executeQuery("SELECT count(*) FROM task_extensions").use { assertTrue(it.next()); assertEquals(0L, it.getLong(1)) }
                    statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 7").use { assertTrue(it.next()); assertEquals("create_task_details_and_weekly_series", it.getString(1)) }
                } }
            }
        }
    }

    @Test
    fun `fixed system projection migration is atomic and preserves human data on retry and reopen`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            val preserved = lease.openConnection().use { connection ->
                seedLegacySystemProjections(connection)
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM schema_migrations WHERE version >= 6")
                    statement.execute(
                        "CREATE FUNCTION reject_system_projection_receipt() RETURNS trigger LANGUAGE plpgsql AS " +
                            "'BEGIN IF NEW.version = 6 THEN RAISE EXCEPTION ''fixture receipt failure''; END IF; RETURN NEW; END;'",
                    )
                    statement.execute(
                        "CREATE TRIGGER reject_system_receipt BEFORE INSERT ON schema_migrations " +
                            "FOR EACH ROW EXECUTE FUNCTION reject_system_projection_receipt()",
                    )
                }
                preservedSystemChatRows(connection)
            }
            assertFailsWith<Exception> { open(lease).close() }
            lease.openConnection().use { connection ->
                assertFixedSystemProjectionCount(connection, 2)
                assertEquals(preserved, preservedSystemChatRows(connection))
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM schema_migrations WHERE version = 6").use { row ->
                        assertTrue(row.next()); assertEquals(0, row.getInt(1))
                    }
                    statement.execute("DROP TRIGGER reject_system_receipt ON schema_migrations")
                    statement.execute("DROP FUNCTION reject_system_projection_receipt()")
                }
            }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            val receipt = lease.openConnection().use { connection ->
                assertFixedSystemProjectionCount(connection, 0)
                assertEquals(preserved, preservedSystemChatRows(connection))
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name, applied_at FROM schema_migrations WHERE version = 6").use { row ->
                        assertTrue(row.next())
                        assertEquals("remove_fixed_system_conversation_projections", row.getString(1))
                        row.getLong(2)
                    }
                }
            }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection ->
                assertFixedSystemProjectionCount(connection, 0)
                assertEquals(preserved, preservedSystemChatRows(connection))
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT applied_at FROM schema_migrations WHERE version = 6").use { row ->
                        assertTrue(row.next()); assertEquals(receipt, row.getLong(1))
                    }
                }
            }
        }
    }

    /** 已有双方 Conversation 的系统私聊，以及不属于固定系统账号的服务身份。 */
    private fun seedLegacySystemProjections(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("""
                INSERT INTO users (uid, username, name, password_hash, role, created_at, updated_at) VALUES
                ('kept-human', 'kept-human', 'kept human', 'fixture-only', 0, 11, 12),
                ('sys_assistant', 'sys_assistant', 'assistant', 'fixture-only', 20, 11, 12),
                ('sys_service', 'sys_service', 'service', 'fixture-only', 20, 11, 12),
                ('kept-other-system', 'kept-other-system', 'other service', 'fixture-only', 20, 11, 12)
            """.trimIndent())
            statement.execute("""
                INSERT INTO chats (chat_id, chat_type, max_seq, created_at, updated_at) VALUES
                ('kept-assistant-chat', 1, 8, 11, 12), ('kept-service-chat', 1, 9, 11, 12),
                ('kept-other-chat', 1, 1, 11, 12)
            """.trimIndent())
            statement.execute("""
                INSERT INTO group_members (uid, chat_id, chat_type, joined_at) VALUES
                ('kept-human', 'kept-assistant-chat', 1, 11), ('sys_assistant', 'kept-assistant-chat', 1, 11),
                ('kept-human', 'kept-service-chat', 1, 11), ('sys_service', 'kept-service-chat', 1, 11),
                ('kept-human', 'kept-other-chat', 1, 11), ('kept-other-system', 'kept-other-chat', 1, 11)
            """.trimIndent())
            statement.execute("""
                INSERT INTO conversations (uid, chat_id, chat_type, last_msg_seq, draft, version, updated_at) VALUES
                ('kept-human', 'kept-assistant-chat', 1, 8, 'kept draft', 17, 12),
                ('kept-human', 'kept-service-chat', 1, 9, NULL, 18, 12),
                ('kept-human', 'kept-other-chat', 1, 1, NULL, 1, 12),
                ('sys_assistant', 'kept-assistant-chat', 1, 8, NULL, 17, 12),
                ('sys_service', 'kept-service-chat', 1, 9, NULL, 18, 12),
                ('kept-other-system', 'kept-other-chat', 1, 1, NULL, 1, 12)
            """.trimIndent())
            statement.execute("""
                INSERT INTO conversation_usages (uid, conversation_count, draft_characters, updated_at) VALUES
                ('kept-human', 3, 10, 12), ('sys_assistant', 1, 0, 12), ('sys_service', 1, 0, 12),
                ('kept-other-system', 1, 0, 12)
            """.trimIndent())
            statement.execute("""
                INSERT INTO chat_draft_assets (uid, chat_id, path)
                VALUES ('kept-human', 'kept-assistant-chat', 'kept/attachment.txt')
            """.trimIndent())
        }
    }

    private fun assertFixedSystemProjectionCount(connection: Connection, expected: Int) {
        listOf("conversations", "conversation_usages").forEach { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table WHERE uid IN ('sys_assistant', 'sys_service')").use { row ->
                    assertTrue(row.next()); assertEquals(expected, row.getInt(1))
                }
            }
        }
    }

    private fun preservedSystemChatRows(connection: Connection): Map<String, List<String>> =
        listOf("users", "chats", "group_members", "chat_draft_assets", "conversations", "conversation_usages").associateWith { table ->
            val filter = if (table == "conversations" || table == "conversation_usages") {
                "WHERE uid NOT IN ('sys_assistant', 'sys_service')"
            } else ""
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT row_to_json(r)::text FROM $table r $filter ORDER BY row_to_json(r)::text").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    @Test
    fun `v0_0_2 migration recreates dropped tables without changing the existing dataset or users`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                // This isolated fixture emulates the immediately preceding schema, never a live instance.
                statement.execute(
                    "DROP TABLE admin_security_audits, admin_security_credentials, document_comments, " +
                        "content_search_pending, task_commands, task_audits, task_extensions, task_attachment_paths, " +
                        "task_deferrals, task_series_attachment_paths, task_series, work_tasks, " +
                        "chat_draft_commands, chat_draft_assets, chat_drafts, " +
                        "oem_push_registrations, admin_feature_settings",
                )
                statement.execute("DELETE FROM schema_migrations WHERE version >= 2")
                statement.execute("INSERT INTO users (uid, username, name, password_hash, created_at, updated_at) " +
                    "VALUES ('kept-table-owner', 'kept-table-owner', 'kept owner', 'fixture-only', 11, 12)")
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 2").use {
                    assertTrue(it.next()); assertEquals("create_v0_0_2_tables", it.getString(1))
                }
                statement.executeQuery("SELECT name, created_at FROM users WHERE uid = 'kept-table-owner'").use {
                    assertTrue(it.next()); assertEquals("kept owner", it.getString(1)); assertEquals(11L, it.getLong(2))
                }
                listOf(
                    "admin_security_credentials", "admin_security_audits", "document_comments",
                    "content_search_pending", "work_tasks", "task_audits", "task_commands",
                    "chat_drafts", "chat_draft_assets", "chat_draft_commands",
                    "oem_push_registrations", "admin_feature_settings",
                ).forEach { table ->
                    statement.executeQuery("SELECT count(*) FROM $table").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                }
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
        }
    }

    @Test
    fun `v0_0_2 migration preserves existing conversation draft rows and restores the mentioned column`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.execute("DROP TABLE chat_draft_commands, chat_draft_assets, chat_drafts")
                statement.execute("ALTER TABLE conversations DROP COLUMN mentioned")
                statement.execute("DELETE FROM schema_migrations WHERE version >= 2")
                statement.execute("INSERT INTO conversations (uid, chat_id, chat_type, draft, version, updated_at) " +
                    "VALUES ('kept-draft-owner', 'kept-draft-chat', 1, 'existing markdown', 8, 12)")
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT draft, version, mentioned FROM conversations WHERE uid = 'kept-draft-owner'").use {
                    assertTrue(it.next()); assertEquals("existing markdown", it.getString(1)); assertEquals(8L, it.getLong(2))
                    assertFalse(it.getBoolean(3))
                }
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 2").use {
                    assertTrue(it.next()); assertEquals("create_v0_0_2_tables", it.getString(1))
                }
                listOf("chat_drafts", "chat_draft_assets", "chat_draft_commands").forEach { table ->
                    statement.executeQuery("SELECT count(*) FROM $table").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                }
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
        }
    }

    @Test
    fun `v0_0_2 migration recreates oem push registrations and preserves credentials`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.execute("DROP TABLE oem_push_registrations")
                statement.execute("DELETE FROM schema_migrations WHERE version >= 2")
                statement.execute("INSERT INTO users (uid, username, name, password_hash, created_at, updated_at) " +
                    "VALUES ('push-kept-user', 'push-kept-user', 'kept', 'fixture-only', 11, 12)")
                statement.execute("INSERT INTO credentials (token_hash, token_type, uid, device_id, device_flag, " +
                    "user_credential_epoch, device_credential_epoch, created_at, expires_at) " +
                    "VALUES ('fixture-refresh-hash', 2, 'push-kept-user', 'device', 1, 1, 1, 11, 99)")
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 2").use {
                    assertTrue(it.next()); assertEquals("create_v0_0_2_tables", it.getString(1))
                }
                statement.executeQuery("SELECT expires_at FROM credentials WHERE token_hash = 'fixture-refresh-hash'").use {
                    assertTrue(it.next()); assertEquals(99L, it.getLong(1))
                }
                statement.executeQuery("SELECT count(*) FROM oem_push_registrations").use {
                    assertTrue(it.next()); assertEquals(0, it.getInt(1))
                }
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
        }
    }

    @Test
    fun `existing byte protocol constraint upgrades without replacing rows or dataset and reopens idempotently`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection ->
                restoreLegacyTelemetryLayout(connection, removeLedger = true)
                assertEquals("23514", assertFailsWith<SQLException> { updateProtocol(connection, 256) }.sqlState)
            }

            open(lease).use { assertEquals(datasetId, it.datasetId) }
            val firstReceipt = lease.openConnection().use { connection ->
                assertPreservedDevice(connection, 255)
                listOf(256, 1 shl 16, Int.MAX_VALUE).forEach { version ->
                    updateProtocol(connection, version)
                    assertPreservedDevice(connection, version)
                }
                assertEquals("23514", assertFailsWith<SQLException> { updateProtocol(connection, -1) }.sqlState)
                migrationReceipt(connection)
            }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection ->
                assertPreservedDevice(connection, Int.MAX_VALUE)
                assertEquals(firstReceipt, migrationReceipt(connection))
                connection.createStatement().use {
                    it.executeUpdate("INSERT INTO schema_migrations SELECT max(version) + 1, 'future_migration', 1 FROM schema_migrations")
                }
            }
            assertFailsWith<IllegalStateException> { open(lease).close() }
            lease.openConnection().use { assertPreservedDevice(it, Int.MAX_VALUE) }
        }
    }

    @Test
    fun `migration receipt failure rolls back the constraint change before startup can publish`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection ->
                restoreLegacyTelemetryLayout(connection, removeLedger = false)
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE FUNCTION reject_migration_receipt() RETURNS trigger LANGUAGE plpgsql AS " +
                            "'BEGIN RAISE EXCEPTION ''fixture receipt failure''; END;'",
                    )
                    statement.execute(
                        "CREATE TRIGGER reject_receipt BEFORE INSERT ON schema_migrations " +
                            "FOR EACH ROW EXECUTE FUNCTION reject_migration_receipt()",
                    )
                }
            }
            assertFailsWith<Exception> { open(lease).close() }
            lease.openConnection().use { connection ->
                assertPreservedDevice(connection, 255)
                assertEquals("23514", assertFailsWith<SQLException> { updateProtocol(connection, 256) }.sqlState)
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM schema_migrations").use {
                        assertTrue(it.next())
                        assertEquals(0, it.getInt(1))
                    }
                    statement.execute("DROP TRIGGER reject_receipt ON schema_migrations")
                    statement.execute("DROP FUNCTION reject_migration_receipt()")
                }
            }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection ->
                updateProtocol(connection, Int.MAX_VALUE)
                assertPreservedDevice(connection, Int.MAX_VALUE)
            }
        }
    }

    private fun open(lease: PostgresSchemaLease) = DatabaseFactory.create(
        jdbcUrl = lease.jdbcUrl,
        user = lease.user,
        password = lease.password,
        maxPoolSize = 1,
    )

    /** Reproduce the deployed epoch-1 layout and a real row, without changing its dataset metadata. */
    private fun restoreLegacyTelemetryLayout(connection: Connection, removeLedger: Boolean) {
        connection.createStatement().use { statement ->
            statement.execute(if (removeLedger) "DROP TABLE schema_migrations" else "DELETE FROM schema_migrations")
            statement.execute(
                "ALTER TABLE client_telemetry_devices DROP CONSTRAINT ck_client_telemetry_device_protocol_version",
            )
            statement.execute(
                "ALTER TABLE client_telemetry_devices ADD CONSTRAINT ck_client_telemetry_device_protocol_version " +
                    "CHECK (protocol_version >= 0 AND protocol_version <= 255)",
            )
            statement.executeUpdate(
                "INSERT INTO client_telemetry_devices " +
                    "(uid, device_id, platform, os_name, os_version, architecture, device_model, app_version, " +
                    "build_number, git_commit, build_identity, build_time, protocol_version, distribution, " +
                    "first_seen_at, last_seen_at, runtime_observed_at) VALUES " +
                    "('kept-user', 'kept-device', 'desktop', 'test', '1', 'test', 'test', '0.0.0', " +
                    "'0', 'test', 'test', 'test', 255, 'test', 42, 43, 44)",
            )
        }
    }

    private fun updateProtocol(connection: Connection, version: Int) {
        connection.prepareStatement("UPDATE client_telemetry_devices SET protocol_version = ? WHERE uid = 'kept-user'").use {
            it.setInt(1, version)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun assertPreservedDevice(connection: Connection, version: Int) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT uid, device_id, app_version, protocol_version, first_seen_at FROM client_telemetry_devices",
            ).use { row ->
                assertTrue(row.next())
                assertEquals("kept-user", row.getString(1))
                assertEquals("kept-device", row.getString(2))
                assertEquals("0.0.0", row.getString(3))
                assertEquals(version, row.getInt(4))
                assertEquals(42L, row.getLong(5))
                assertTrue(!row.next())
            }
        }
    }

    private fun migrationReceipt(connection: Connection): Long = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT version, name, applied_at FROM schema_migrations ORDER BY version").use { row ->
            assertTrue(row.next())
            assertEquals(0, row.getInt(1))
            assertEquals("expand_client_telemetry_protocol_id", row.getString(2))
            assertTrue(row.next())
            assertEquals(1, row.getInt(1))
            assertEquals("create_banned_credential_tombstones", row.getString(2))
            row.getLong(3).also {
                assertTrue(it > 0L)
                var expectedVersion = 2
                while (row.next()) {
                    assertEquals(expectedVersion++, row.getInt(1))
                    assertTrue(row.getString(2).isNotBlank())
                    assertTrue(row.getLong(3) > 0L)
                }
                assertTrue(expectedVersion >= 3)
            }
        }
    }
}
