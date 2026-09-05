package com.virjar.tk.server.infra.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableUidSignalMailboxTest {
    @Test
    fun `same uid storm occupies one slot without drops`() {
        val mailbox = DurableUidSignalMailbox(capacity = 2)

        repeat(100_000) { mailbox.offerAll(setOf("u1")) }

        assertEquals(1, mailbox.pendingCount)
        assertEquals(0L, mailbox.droppedCount)
        assertEquals(setOf("u1"), mailbox.drain().uids)
    }

    @Test
    fun `overflow is bounded and forces durable scan recovery`() {
        val mailbox = DurableUidSignalMailbox(capacity = 2)

        mailbox.offerAll(linkedSetOf("u1", "u2", "u3", "u4"))
        val first = mailbox.drain()

        assertEquals(setOf("u1", "u2"), first.uids)
        assertTrue(first.overflowed)
        assertEquals(2L, mailbox.droppedCount)
        assertEquals(0, mailbox.pendingCount)

        // 数据库扫描失败会让恢复义务在下一轮仍然可见。
        assertTrue(mailbox.drain().overflowed)
        mailbox.acknowledgeOverflowRecovery(first.overflowVersion)
        assertFalse(mailbox.drain().overflowed)

        mailbox.offerAll(setOf("u3"))
        val recovered = mailbox.drain()
        assertEquals(setOf("u3"), recovered.uids)
        assertFalse(recovered.overflowed)
    }

    @Test
    fun `close clears hints and rejects late work`() {
        val mailbox = DurableUidSignalMailbox(capacity = 1)
        mailbox.offerAll(setOf("u1"))

        mailbox.close()
        mailbox.offerAll(setOf("u2"))

        assertEquals(DurableUidSignalBatch(emptySet(), false), mailbox.drain())
    }

    @Test
    fun `drop report thresholds grow geometrically without overflow`() {
        assertEquals(2L, nextSyncDropReportThreshold(1L))
        assertEquals(4L, nextSyncDropReportThreshold(2L))
        assertEquals(4L, nextSyncDropReportThreshold(3L))
        assertEquals(Long.MAX_VALUE, nextSyncDropReportThreshold(Long.MAX_VALUE))
    }
}
