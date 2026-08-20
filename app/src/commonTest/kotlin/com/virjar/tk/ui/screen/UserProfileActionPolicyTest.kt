package com.virjar.tk.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserProfileActionPolicyTest {

    @Test
    fun `friend can expose delete and block actions independently`() {
        assertEquals(
            listOf(
                UserProfileDestructiveAction.DeleteFriend,
                UserProfileDestructiveAction.BlockUser,
            ),
            availableUserProfileDestructiveActions(
                isFriend = true,
                hasDeleteFriendAction = true,
                hasBlockUserAction = true,
            ),
        )
    }

    @Test
    fun `non friend can still be blocked but cannot be deleted`() {
        assertEquals(
            listOf(UserProfileDestructiveAction.BlockUser),
            availableUserProfileDestructiveActions(
                isFriend = false,
                hasDeleteFriendAction = true,
                hasBlockUserAction = true,
            ),
        )
    }

    @Test
    fun `caller hides block action by omitting callback`() {
        assertEquals(
            emptyList(),
            availableUserProfileDestructiveActions(
                isFriend = false,
                hasDeleteFriendAction = false,
                hasBlockUserAction = false,
            ),
        )
    }

    @Test
    fun `destructive action requires request then confirmation dismissal`() {
        val available = listOf(UserProfileDestructiveAction.BlockUser)

        val requested = UserProfileActionUiState().request(
            UserProfileDestructiveAction.BlockUser,
            available,
        )
        assertEquals(UserProfileDestructiveAction.BlockUser, requested.pendingConfirmation)

        val dismissed = requested.dismissConfirmation()
        assertNull(dismissed.pendingConfirmation)
    }

    @Test
    fun `unavailable destructive action cannot enter confirmation state`() {
        val state = UserProfileActionUiState().request(
            UserProfileDestructiveAction.BlockUser,
            availableActions = emptyList(),
        )

        assertNull(state.pendingConfirmation)
    }
}
