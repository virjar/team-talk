package com.virjar.tk.navigation.feature

import com.virjar.tk.model.ContactApplyRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileFriendApplyStateTest {
    @Test
    fun `outgoing pending is rendered as already applied`() {
        assertEquals(
            ProfileFriendApplyState.OUTGOING_PENDING,
            profileFriendApplyState(record(direction = ContactApplyRecord.DIRECTION_OUTGOING)),
        )
    }

    @Test
    fun `incoming pending is not confused with an outgoing application`() {
        assertEquals(
            ProfileFriendApplyState.INCOMING_PENDING,
            profileFriendApplyState(record(direction = ContactApplyRecord.DIRECTION_INCOMING)),
        )
    }

    @Test
    fun `missing or processed records do not leave a pending profile state`() {
        assertEquals(ProfileFriendApplyState.NONE, profileFriendApplyState(null))
        assertEquals(
            ProfileFriendApplyState.NONE,
            profileFriendApplyState(
                record(
                    direction = ContactApplyRecord.DIRECTION_OUTGOING,
                    status = ContactApplyRecord.STATUS_ACCEPTED,
                ),
            ),
        )
    }

    private fun record(
        direction: Int,
        status: Int = ContactApplyRecord.STATUS_PENDING,
    ) = ContactApplyRecord(
        id = 1,
        fromUid = "me",
        toUid = "peer",
        direction = direction,
        status = status,
        createdAt = 10,
        updatedAt = 10,
    )
}
