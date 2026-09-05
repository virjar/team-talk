package com.virjar.tk.server.infra.db

import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Transaction
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.concurrent.Executor

/** 进程级 JDBC 边界；URL 参数无法静默禁用这些自有默认值。 */
internal object PostgresConnectionTimeoutPolicy {
    const val POOL_ACQUISITION_TIMEOUT_MILLIS = 3_000L
    const val POOL_VALIDATION_TIMEOUT_MILLIS = 2_000L
    const val DRIVER_CONNECT_TIMEOUT_SECONDS = 3
    const val DRIVER_LOGIN_TIMEOUT_SECONDS = 3
    const val DRIVER_SOCKET_TIMEOUT_SECONDS = 30
    const val DRIVER_CANCEL_TIMEOUT_SECONDS = 3

    private val managedDriverProperties = mapOf(
        "connecttimeout" to ManagedDriverProperty("connectTimeout", maximumSeconds = 10),
        "logintimeout" to ManagedDriverProperty("loginTimeout", maximumSeconds = 10),
        "sockettimeout" to ManagedDriverProperty("socketTimeout", maximumSeconds = 60),
        "cancelsignaltimeout" to ManagedDriverProperty("cancelSignalTimeout", maximumSeconds = 10),
    )

    fun apply(dataSource: HikariDataSource) {
        dataSource.connectionTimeout = POOL_ACQUISITION_TIMEOUT_MILLIS
        dataSource.validationTimeout = POOL_VALIDATION_TIMEOUT_MILLIS
        dataSource.addDataSourceProperty("connectTimeout", DRIVER_CONNECT_TIMEOUT_SECONDS.toString())
        dataSource.addDataSourceProperty("loginTimeout", DRIVER_LOGIN_TIMEOUT_SECONDS.toString())
        dataSource.addDataSourceProperty("socketTimeout", DRIVER_SOCKET_TIMEOUT_SECONDS.toString())
        dataSource.addDataSourceProperty("cancelSignalTimeout", DRIVER_CANCEL_TIMEOUT_SECONDS.toString())
        dataSource.addDataSourceProperty("tcpKeepAlive", "true")
    }

    fun requireBoundedJdbcUrlTimeouts(jdbcUrl: String) {
        jdbcUrl.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .forEach { token ->
                val normalizedName = token.substringBefore('=').filter(Char::isLetterOrDigit).lowercase()
                val property = managedDriverProperties[normalizedName] ?: return@forEach
                val seconds = token.substringAfter('=', missingDelimiterValue = "")
                    .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                    .toIntOrNull()
                require(seconds != null && seconds in 1..property.maximumSeconds) {
                    "PostgreSQL JDBC URL ${property.name} must be between 1 and " +
                        "${property.maximumSeconds} seconds"
                }
            }
    }

    private data class ManagedDriverProperty(
        val name: String,
        val maximumSeconds: Int,
    )
}

/**
 * 仅用于把关 `/health` 就绪状态的少量 PostgreSQL 读操作的硬限制。
 *
 * 仅靠协程取消无法中止一次 JDBC socket 读。因此健康事务
 * 组合了 JDBC 语句超时与更短的每连接网络超时，并禁用
 * Exposed 重试。调用方仍持有外层协程截止时间，使连接池获取也可被中断，
 * 而 Hikari 自身的获取上限仍是最后的非协程边界。
 */
internal object PostgresHealthProbePolicy {
    const val OUTER_TIMEOUT_MILLIS = 4_000L
    const val STATEMENT_TIMEOUT_SECONDS = 2
    const val NETWORK_TIMEOUT_MILLIS = 3_000

    private val directExecutor = Executor { command -> command.run() }

    fun <T> run(transaction: Transaction, block: Transaction.() -> T): T {
        transaction.maxAttempts = 1
        transaction.queryTimeout = STATEMENT_TIMEOUT_SECONDS

        val jdbcConnection = transaction.connection.connection as? Connection
            ?: error("PostgreSQL health probe requires a JDBC connection")
        val previousNetworkTimeout = jdbcConnection.networkTimeout
        val changedNetworkTimeout = previousNetworkTimeout == 0 ||
            previousNetworkTimeout > NETWORK_TIMEOUT_MILLIS
        if (changedNetworkTimeout) {
            jdbcConnection.setNetworkTimeout(directExecutor, NETWORK_TIMEOUT_MILLIS)
        }

        var primaryFailure: Throwable? = null
        try {
            return transaction.block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (changedNetworkTimeout && !jdbcConnection.isClosed) {
                try {
                    jdbcConnection.setNetworkTimeout(directExecutor, previousNetworkTimeout)
                } catch (restoreFailure: Throwable) {
                    val primary = primaryFailure
                    if (primary == null) {
                        throw restoreFailure
                    }
                    primary.addSuppressed(restoreFailure)
                }
            }
        }
    }
}
