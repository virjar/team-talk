package com.virjar.tk.server.testing

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 为进程内服务器测试持有一个可丢弃的 PostgreSQL schema。
 *
 * 测试可以共享同一个 PostgreSQL 数据库，但绝不能与开发环境或其他测试共享 schema。调用方必须
 * 在关闭本租约之前先关闭其 [PostgresDatabase][com.virjar.tk.server.infra.db.PostgresDatabase] 句柄，
 * 这样只有该环境的连接池会在 schema 被删除前释放它。
 */
internal class PostgresSchemaLease private constructor(
    private val baseJdbcUrl: String,
    val user: String,
    val password: String,
    val schemaName: String,
) : AutoCloseable {
    val jdbcUrl: String = jdbcUrlWithCurrentSchema(baseJdbcUrl, schemaName)

    private val closed = AtomicBoolean(false)

    fun openConnection(): Connection = DriverManager.getConnection(jdbcUrl, user, password)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            DriverManager.getConnection(baseJdbcUrl, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA ${quotedTestSchema(schemaName)} CASCADE")
                }
            }
        } catch (error: Throwable) {
            closed.set(false)
            throw error
        }
    }

    companion object {
        fun open(
            baseJdbcUrl: String = System.getenv("TK_TEST_PG_JDBC")
                ?: "jdbc:postgresql://localhost:5432/teamtalk",
            user: String = System.getenv("TK_TEST_PG_USER") ?: System.getProperty("user.name"),
            password: String = System.getenv("TK_TEST_PG_PASSWORD") ?: "",
        ): PostgresSchemaLease {
            require(baseJdbcUrl.startsWith(POSTGRES_JDBC_PREFIX)) {
                "TK_TEST_PG_JDBC must be a PostgreSQL JDBC URL"
            }
            val schemaName = newTestSchemaName()
            DriverManager.getConnection(baseJdbcUrl, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA ${quotedTestSchema(schemaName)}")
                }
            }
            return PostgresSchemaLease(baseJdbcUrl, user, password, schemaName)
        }
    }
}

internal fun newTestSchemaName(suffix: String = UUID.randomUUID().toString().replace("-", "")): String {
    val schemaName = "tt_test_${suffix.lowercase()}"
    require(TEST_SCHEMA.matches(schemaName)) { "Unsafe PostgreSQL test schema name: $schemaName" }
    return schemaName
}

internal fun jdbcUrlWithCurrentSchema(jdbcUrl: String, schemaName: String): String {
    require(jdbcUrl.startsWith(POSTGRES_JDBC_PREFIX)) { "Expected a PostgreSQL JDBC URL" }
    require(TEST_SCHEMA.matches(schemaName)) { "Unsafe PostgreSQL test schema name: $schemaName" }

    val questionMark = jdbcUrl.indexOf('?')
    val base = if (questionMark < 0) jdbcUrl else jdbcUrl.substring(0, questionMark)
    val parameters = if (questionMark < 0) {
        emptyList()
    } else {
        jdbcUrl.substring(questionMark + 1)
            .split('&')
            .filter { it.isNotBlank() }
            .filterNot { it.substringBefore('=').equals("currentSchema", ignoreCase = true) }
    }
    return buildString {
        append(base)
        append('?')
        if (parameters.isNotEmpty()) {
            append(parameters.joinToString("&"))
            append('&')
        }
        append("currentSchema=")
        append(schemaName)
    }
}

private fun quotedTestSchema(schemaName: String): String {
    require(TEST_SCHEMA.matches(schemaName)) { "Unsafe PostgreSQL test schema name: $schemaName" }
    return "\"$schemaName\""
}

private const val POSTGRES_JDBC_PREFIX = "jdbc:postgresql:"
private val TEST_SCHEMA = Regex("tt_test_[a-z0-9]{8,64}")
