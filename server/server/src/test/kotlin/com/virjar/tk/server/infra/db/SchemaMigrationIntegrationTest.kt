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
                    it.executeUpdate("INSERT INTO schema_migrations VALUES (1, 'future_migration', 1)")
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
        statement.executeQuery("SELECT version, name, applied_at FROM schema_migrations").use { row ->
            assertTrue(row.next())
            assertEquals(0, row.getInt(1))
            assertEquals("expand_client_telemetry_protocol_id", row.getString(2))
            row.getLong(3).also {
                assertTrue(it > 0L)
                assertTrue(!row.next())
            }
        }
    }
}
