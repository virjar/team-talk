package com.virjar.tk.server.infra.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncEventRetentionConfigTest {
    @Test
    fun `retention defaults to thirty days and accepts configured boundaries`() {
        val day = 24L * 60L * 60L * 1_000L

        assertEquals(30L * day, SyncEventRetentionConfig.fromEnvironment { null }.retentionMillis)
        assertEquals(
            day,
            SyncEventRetentionConfig.fromEnvironment { name ->
                if (name == SyncEventRetentionConfig.RETENTION_DAYS_ENV) "1" else null
            }.retentionMillis,
        )
        assertEquals(
            3_650L * day,
            SyncEventRetentionConfig.fromEnvironment { "3650" }.retentionMillis,
        )
    }

    @Test
    fun `retention rejects malformed or unbounded configuration`() {
        assertFailsWith<IllegalArgumentException> {
            SyncEventRetentionConfig.fromEnvironment { "not-a-number" }
        }
        assertFailsWith<IllegalArgumentException> {
            SyncEventRetentionConfig.fromEnvironment { "0" }
        }
        assertFailsWith<IllegalArgumentException> {
            SyncEventRetentionConfig.fromEnvironment { "3651" }
        }
    }
}
