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
    fun `v0_0_2 migration recreates dropped tables without changing the existing dataset or users`() {
        PostgresSchemaLease.open().use { lease ->
            val datasetId = open(lease).use { it.datasetId }
            lease.openConnection().use { connection -> connection.createStatement().use { statement ->
                // This isolated fixture emulates the immediately preceding schema, never a live instance.
                statement.execute(
                    "DROP TABLE admin_security_audits, admin_security_credentials, document_comments, " +
                        "content_search_pending, task_commands, task_audits, work_tasks, " +
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
