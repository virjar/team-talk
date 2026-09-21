package com.virjar.tk.app.navigation.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ChatDraftLifecycleBridgeTest {
    @Test
    fun backgroundCaptureKeepsTheEditorRegisteredUntilItsActualRetirement() {
        val bridge = ChatDraftLifecycleBridge()
        var text = "中文输入中的草稿"
        val persisted = mutableListOf<String>()
        val registration = bridge.register { bridge.publishIfOpen { persisted += text } }

        bridge.captureLatest()
        text = "回到前台继续编辑"
        bridge.captureLatest()
        text = "注销前最后一帧"
        bridge.captureAndRetire()
        bridge.captureLatest()
        bridge.captureAndUnregister(registration)

        assertEquals(listOf("中文输入中的草稿", "回到前台继续编辑", "注销前最后一帧"), persisted)
        assertFalse(bridge.publishIfOpen { error("retired editor must not publish") })
    }

    @Test
    fun backgroundFailureStillCapturesOtherEditorsAndAllowsLaterRetry() {
        val bridge = ChatDraftLifecycleBridge()
        val diskFailure = IllegalStateException("disk write failed")
        var failing = true
        var otherCaptured = 0
        bridge.register { if (failing) throw diskFailure }
        bridge.register { otherCaptured++ }

        assertSame(diskFailure, assertFailsWith<IllegalStateException> { bridge.captureLatest() })
        assertEquals(1, otherCaptured)
        failing = false
        bridge.captureLatest()
        bridge.captureAndRetire()
        assertEquals(3, otherCaptured)
    }
}
