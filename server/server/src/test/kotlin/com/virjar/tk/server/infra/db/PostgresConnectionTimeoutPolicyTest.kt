package com.virjar.tk.server.infra.db

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PostgresConnectionTimeoutPolicyTest {
    @Test
    fun `pool and driver always have finite connection boundaries`() {
        val dataSource = HikariDataSource()
        try {
            PostgresConnectionTimeoutPolicy.apply(dataSource)

            assertEquals(
                PostgresConnectionTimeoutPolicy.POOL_ACQUISITION_TIMEOUT_MILLIS,
                dataSource.connectionTimeout,
            )
            assertEquals(
                PostgresConnectionTimeoutPolicy.POOL_VALIDATION_TIMEOUT_MILLIS,
                dataSource.validationTimeout,
            )
            assertEquals(
                PostgresConnectionTimeoutPolicy.DRIVER_CONNECT_TIMEOUT_SECONDS.toString(),
                dataSource.dataSourceProperties.getProperty("connectTimeout"),
            )
            assertEquals(
                PostgresConnectionTimeoutPolicy.DRIVER_LOGIN_TIMEOUT_SECONDS.toString(),
                dataSource.dataSourceProperties.getProperty("loginTimeout"),
            )
            assertEquals(
                PostgresConnectionTimeoutPolicy.DRIVER_SOCKET_TIMEOUT_SECONDS.toString(),
                dataSource.dataSourceProperties.getProperty("socketTimeout"),
            )
            assertEquals(
                PostgresConnectionTimeoutPolicy.DRIVER_CANCEL_TIMEOUT_SECONDS.toString(),
                dataSource.dataSourceProperties.getProperty("cancelSignalTimeout"),
            )
            assertEquals("true", dataSource.dataSourceProperties.getProperty("tcpKeepAlive"))
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `jdbc url cannot silently disable owned timeout policy`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PostgresConnectionTimeoutPolicy.requireBoundedJdbcUrlTimeouts(
                "jdbc:postgresql://localhost/teamtalk?currentSchema=tenant&socketTimeout=0&" +
                    "applicationName=private-marker",
            )
        }

        assertFalse(error.message.orEmpty().contains("private-marker"))
        PostgresConnectionTimeoutPolicy.requireBoundedJdbcUrlTimeouts(
            "jdbc:postgresql://localhost/teamtalk?currentSchema=tenant&connectTimeout=5",
        )
    }
}
