package com.virjar.tk.ui.screen

import com.virjar.tk.model.User
import com.virjar.tk.model.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserProfileActionPolicyTest {

    @Test
    fun `friend request is available only for another human user`() {
        val human = User(uid = "human", username = "human", name = "Human", role = UserRole.HUMAN)
        val bot = User(uid = "bot", username = "bot", name = "Bot", role = UserRole.BOT)
        val system = User(uid = "system", username = "system", name = "System", role = UserRole.SYSTEM)

        assertEquals(true, canAddFriendFromProfile(human, myUid = "me"))
        assertEquals(false, canAddFriendFromProfile(human, myUid = "human"))
        assertEquals(false, canAddFriendFromProfile(bot, myUid = "me"))
        assertEquals(false, canAddFriendFromProfile(system, myUid = "me"))
    }

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
