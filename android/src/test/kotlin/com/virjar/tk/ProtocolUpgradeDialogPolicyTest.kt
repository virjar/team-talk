package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProtocolUpgradeDialogPolicyTest {
    @Test
    fun `forced upgrade prompt cannot be dismissed without exiting`() {
        val policy = forceProtocolUpgradeDialogPolicy

        assertFalse(policy.dismissOnBackPress)
        assertFalse(policy.dismissOnClickOutside)
        assertEquals("退出应用", policy.confirmLabel)
    }
}
