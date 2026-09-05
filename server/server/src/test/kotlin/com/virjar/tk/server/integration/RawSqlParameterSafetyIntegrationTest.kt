package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.execRawSql
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawSqlParameterSafetyIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `raw SQL execution cannot mutate table column nullability`() {
        val tableOwnedColumnType = Users.uid.columnType
        assertFalse(tableOwnedColumnType.nullable)

        val selected = transaction(ctx.database) {
            execRawSql(
                stmt = "SELECT ?::varchar AS bound_value, ?::varchar IS NULL AS null_value",
                args = listOf(
                    tableOwnedColumnType to "raw-sql-nullability-probe",
                    tableOwnedColumnType to null,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getString("bound_value") to resultSet.getBoolean("null_value")
            }
        }

        assertEquals("raw-sql-nullability-probe" to true, selected)
        assertFalse(
            tableOwnedColumnType.nullable,
            "Transaction.exec must only mark the disposable raw SQL wrapper nullable",
        )
        assertFalse(Users.uid.columnType.nullable)
    }
}
