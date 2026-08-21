package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAuthNavigationTest {
    @Test
    fun `system back from registration returns to login`() {
        assertEquals(AuthDestination.LOGIN, AuthDestination.REGISTER.backDestination())
    }

    @Test
    fun `system back on login remains available to the activity`() {
        assertNull(AuthDestination.LOGIN.backDestination())
    }
}
