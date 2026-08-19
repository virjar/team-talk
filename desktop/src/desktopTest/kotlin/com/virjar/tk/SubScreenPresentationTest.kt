package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals

class SubScreenPresentationTest {

    @Test
    fun `group context screens use chat inspector`() {
        val screens = listOf(
            SubScreen.GroupDetail("chat-1"),
            SubScreen.InviteMembers("chat-1"),
            SubScreen.InviteLinks("chat-1"),
        )

        screens.forEach { screen ->
            assertEquals(SubScreenPresentation.CHAT_INSPECTOR, screen.presentation)
        }
    }

    @Test
    fun `global search replaces main pane`() {
        assertEquals(SubScreenPresentation.MAIN_PANE, SubScreen.GlobalSearch.presentation)
        assertEquals(SubScreenPresentation.MAIN_PANE, SubScreen.GroupDocuments("chat-1").presentation)
    }

    @Test
    fun `workflow screens remain task windows`() {
        val screens = listOf(
            SubScreen.Devices,
            SubScreen.SearchUsers,
            SubScreen.CreateGroup(),
        )

        screens.forEach { screen ->
            assertEquals(SubScreenPresentation.TASK_WINDOW, screen.presentation)
        }
    }
}
