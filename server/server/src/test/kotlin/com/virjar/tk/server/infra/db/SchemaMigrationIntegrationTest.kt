package com.virjar.tk.server.infra.db

import com.virjar.tk.server.testing.PostgresSchemaLease
import java.sql.Connection
import java.sql.SQLException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaMigrationIntegrationTest {
    @Test
    fun `task migration appends tables without changing the existing dataset or users`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                // This isolated fixture emulates the immediately preceding schema, never a live instance.
                statement.execute("DROP TABLE chat_draft_commands, chat_draft_assets, chat_drafts, task_commands, task_audits, work_tasks")
                statement.execute("DELETE FROM schema_migrations WHERE version >= 5")
                statement.execute("INSERT INTO users (uid, username, name, password_hash, created_at, updated_at) " +
                    "VALUES ('kept-task-owner', 'kept-task-owner', 'kept owner', 'fixture-only', 11, 12)")
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 5").use {
                    assertTrue(it.next()); assertEquals("create_tasks", it.getString(1))
                }
                statement.executeQuery("SELECT name, created_at FROM users WHERE uid = 'kept-task-owner'").use {
                    assertTrue(it.next()); assertEquals("kept owner", it.getString(1)); assertEquals(11L, it.getLong(2))
                }
                listOf("work_tasks", "task_audits", "task_commands").forEach { table ->
                    statement.executeQuery("SELECT count(*) FROM $table").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                }
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
        }
    }

    @Test
    fun `ready draft migration preserves existing conversation draft and dataset`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.execute("DROP TABLE chat_draft_commands, chat_draft_assets, chat_drafts")
                statement.execute("DELETE FROM schema_migrations WHERE version >= 6")
                statement.execute("INSERT INTO conversations (uid, chat_id, chat_type, draft, version, updated_at) " +
                    "VALUES ('kept-draft-owner', 'kept-draft-chat', 1, 'existing markdown', 8, 12)")
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT draft, version FROM conversations WHERE uid = 'kept-draft-owner'").use {
                    assertTrue(it.next()); assertEquals("existing markdown", it.getString(1)); assertEquals(8L, it.getLong(2))
                }
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 6").use {
                    assertTrue(it.next()); assertEquals("create_chat_drafts", it.getString(1))
                }
                listOf("chat_drafts", "chat_draft_assets", "chat_draft_commands").forEach { table ->
                    statement.executeQuery("SELECT count(*) FROM $table").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                }
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
        }
    }

    @Test
    fun `xiaomi registration migration preserves credentials and dataset`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                // 全新库按最终布局建表；回滚到迁移 7 之前需删除现行表，由账目重建小米旧表。
                statement.execute("DROP TABLE oem_push_registrations")
                statement.execute("DELETE FROM schema_migrations WHERE version >= 7")
                statement.execute("INSERT INTO users (uid, username, name, password_hash, created_at, updated_at) " +
                    "VALUES ('push-kept-user', 'push-kept-user', 'kept', 'fixture-only', 11, 12)")
                statement.execute("INSERT INTO credentials (token_hash, token_type, uid, device_id, device_flag, " +
                    "user_credential_epoch, device_credential_epoch, created_at, expires_at) " +
                    "VALUES ('fixture-refresh-hash', 2, 'push-kept-user', 'device', 1, 1, 1, 11, 99)")
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 7").use {
                    assertTrue(it.next()); assertEquals("create_xiaomi_push_registrations", it.getString(1))
                }
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 8").use {
                    assertTrue(it.next()); assertEquals("generalize_oem_push_registrations", it.getString(1))
                }
                statement.executeQuery("SELECT expires_at FROM credentials WHERE token_hash = 'fixture-refresh-hash'").use {
                    assertTrue(it.next()); assertEquals(99L, it.getLong(1))
                }
                // 迁移 8 把小米表改名为多厂商表；行数断言针对最终表名。
                statement.executeQuery("SELECT count(*) FROM oem_push_registrations").use {
                    assertTrue(it.next()); assertEquals(0, it.getInt(1))
                }
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
        }
    }

    @Test
    fun `oem registration migration renames the xiaomi table and preserves its rows`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                // Emulate the layout right after migration 7: only the xiaomi table exists, with one row.
                statement.execute("DROP TABLE oem_push_registrations")
                statement.execute("DELETE FROM schema_migrations WHERE version >= 7")
                statement.execute("INSERT INTO users (uid, username, name, password_hash, created_at, updated_at) " +
                    "VALUES ('oem-kept-user', 'oem-kept-user', 'kept', 'fixture-only', 11, 12)")
                statement.execute("INSERT INTO credentials (token_hash, token_type, uid, device_id, device_flag, " +
                    "user_credential_epoch, device_credential_epoch, created_at, expires_at) " +
                    "VALUES ('oem-fixture-refresh', 2, 'oem-kept-user', 'device', 1, 1, 1, 11, 99)")
                statement.execute(
                    "CREATE TABLE xiaomi_push_registrations (" +
                        "refresh_token_hash varchar(64) PRIMARY KEY REFERENCES credentials(token_hash) ON DELETE CASCADE, " +
                        "registration_id varchar(4096), registration_hash varchar(64), generation varchar(36), " +
                        "package_name varchar(255), deployment_fingerprint varchar(64), " +
                        "pending_event_id bigint DEFAULT 0, delivered_event_id bigint DEFAULT 0, " +
                        "pending_chats text DEFAULT '{}', attempts integer DEFAULT 0, " +
                        "next_attempt_at bigint DEFAULT 0, last_failure varchar(40))",
                )
                statement.execute(
                    "INSERT INTO xiaomi_push_registrations (refresh_token_hash, registration_id, registration_hash, " +
                        "generation, package_name, deployment_fingerprint) VALUES " +
                        "('oem-fixture-refresh', 'fixture-reg', 'hash', 'gen', 'com.example', '" + "a".repeat(64) + "')",
                )
            } }
            open(lease).use { assertEquals(datasetId, it.datasetId) }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM schema_migrations WHERE version = 8").use {
                    assertTrue(it.next()); assertEquals("generalize_oem_push_registrations", it.getString(1))
                }
                statement.executeQuery(
                    "SELECT vendor, registration_id FROM oem_push_registrations",
                ).use {
                    assertTrue(it.next())
                    assertEquals("xiaomi", it.getString(1))
                    assertEquals("fixture-reg", it.getString(2))
                    assertTrue(!it.next())
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
