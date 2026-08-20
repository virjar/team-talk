package com.virjar.tk.ui.screen

import com.virjar.tk.model.ContactApplyRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendApplyRecordPresentationTest {
    @Test
    fun `outgoing pending record points at target and waits for verification`() {
        val record = record(
            direction = ContactApplyRecord.DIRECTION_OUTGOING,
            status = ContactApplyRecord.STATUS_PENDING,
        )

        assertEquals("peer", friendApplyPeerUid(record))
        assertEquals("等待验证", friendApplyStatusText(record))
        assertEquals("发出的申请 · 等待对方验证", friendApplyDescription(record))
        assertFalse(canProcessFriendApply(record))
    }

    @Test
    fun `only incoming pending record with token exposes processing actions`() {
        val processable = record(
            direction = ContactApplyRecord.DIRECTION_INCOMING,
            status = ContactApplyRecord.STATUS_PENDING,
            token = "token",
        )
        val missingToken = processable.copy(token = null)
        val processed = processable.copy(status = ContactApplyRecord.STATUS_ACCEPTED, token = null)

        assertEquals("peer", friendApplyPeerUid(processable))
        assertEquals("收到的申请 · 等待你处理", friendApplyDescription(processable))
        assertTrue(canProcessFriendApply(processable))
        assertFalse(canProcessFriendApply(missingToken))
        assertFalse(canProcessFriendApply(processed))
    }

    @Test
    fun `processed status remains directional`() {
        assertEquals(
            "发出的申请 · 对方已拒绝",
            friendApplyDescription(
                record(
                    direction = ContactApplyRecord.DIRECTION_OUTGOING,
                    status = ContactApplyRecord.STATUS_REJECTED,
                ),
            ),
        )
        assertEquals(
            "收到的申请 · 你已接受",
            friendApplyDescription(
                record(
                    direction = ContactApplyRecord.DIRECTION_INCOMING,
                    status = ContactApplyRecord.STATUS_ACCEPTED,
                ),
            ),
        )
    }

    @Test
    fun `legacy duplicate pending is described as merged rather than rejected`() {
        val superseded = record(
            direction = ContactApplyRecord.DIRECTION_OUTGOING,
            status = ContactApplyRecord.STATUS_SUPERSEDED,
        )

        assertEquals("已合并", friendApplyStatusText(superseded))
        assertEquals("发出的申请 · 重复申请已合并", friendApplyDescription(superseded))
        assertFalse(canProcessFriendApply(superseded))
    }

    private fun record(
        direction: Int,
        status: Int,
        token: String? = null,
    ) = ContactApplyRecord(
        id = 1,
        fromUid = if (direction == ContactApplyRecord.DIRECTION_INCOMING) "peer" else "me",
        toUid = if (direction == ContactApplyRecord.DIRECTION_INCOMING) "me" else "peer",
        direction = direction,
        token = token,
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )
}
