package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalOutgoingRecoveryComplexityTest {
    @Test
    fun `worker recovery bulk promotes authority without point reads or successful payload decoding`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val driver = InspectingSqlDriver(rawDriver)
        val cache = LocalCacheImpl(driver)
        try {
            repeat(RECOVERY_ROWS) { index ->
                cache.enqueueOutgoingMessage(
                    Message(
                        chatId = "recovery",
                        clientMsgId = "message-$index",
                        senderUid = "owner",
                        messageType = MessageType.RICH_TEXT.code,
                        timestamp = index.toLong(),
                        body = RichTextBody("body-$index", plainText = "body-$index"),
                    ),
                    now = index.toLong(),
                )
            }
            rawDriver.execute(
                null,
                "UPDATE message SET server_seq = 7, send_status = 3 WHERE chat_id = 'recovery'",
                0,
            )
            // 只要解码哪怕一条新成功的回执，worker 就会在这个 payload 上失败。
            rawDriver.execute(null, "UPDATE outgoing_message SET payload = X'00'", 0)
            driver.messagePointReads = 0
            driver.allOutgoingReads = 0

            cache.recoverOutgoingState(now = 100L)

            assertEquals(0, driver.messagePointReads)
            assertEquals(0, driver.allOutgoingReads)
            assertEquals(
                RECOVERY_ROWS.toLong(),
                rawDriver.executeQuery(
                    null,
                    "SELECT COUNT(*) FROM outgoing_message WHERE state = 4",
                    { cursor: SqlCursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
                    },
                    0,
                ).value,
            )
        } finally {
            cache.close()
        }
    }

    @Test
    fun `worker recovery rebuilds missing projections in pages without message point reads`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val driver = InspectingSqlDriver(rawDriver)
        val cache = LocalCacheImpl(driver)
        try {
            repeat(RECOVERY_ROWS) { index ->
                cache.enqueueOutgoingMessage(
                    Message(
                        chatId = "missing",
                        clientMsgId = "message-$index",
                        senderUid = "owner",
                        messageType = MessageType.RICH_TEXT.code,
                        timestamp = index.toLong(),
                        body = RichTextBody("body-$index", plainText = "body-$index"),
                    ),
                    now = index.toLong(),
                )
            }
            rawDriver.execute(null, "DELETE FROM message WHERE chat_id = 'missing'", 0)
            driver.messagePointReads = 0

            cache.recoverOutgoingState(now = 100L)

            assertEquals(0, driver.messagePointReads)
            assertEquals(
                RECOVERY_ROWS.toLong(),
                rawDriver.executeQuery(
                    null,
                    "SELECT COUNT(*) FROM message WHERE chat_id = 'missing' " +
                        "AND send_status = ${Message.SEND_STATUS_QUEUED}",
                    { cursor: SqlCursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
                    },
                    0,
                ).value,
            )
        } finally {
            cache.close()
        }
    }

    private class InspectingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        var messagePointReads = 0
        var allOutgoingReads = 0

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val normalized = sql.replace(Regex("\\s+"), " ")
            if (normalized.contains("FROM message WHERE chat_id = ? AND client_msg_id = ?")) {
                messagePointReads += 1
            }
            if (normalized.contains("SELECT * FROM outgoing_message ORDER BY local_ordinal ASC")) {
                allOutgoingReads += 1
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private companion object {
        const val RECOVERY_ROWS = 40
    }
}
