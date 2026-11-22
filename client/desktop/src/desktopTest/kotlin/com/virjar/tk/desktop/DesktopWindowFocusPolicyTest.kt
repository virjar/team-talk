package com.virjar.tk.desktop

import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面窗口焦点与已读上下文判定测试：覆盖媒体画廊独立窗口、主窗口所属模态弹窗及普通未聚焦状态。
 */
class DesktopWindowFocusPolicyTest {
    @Test
    fun `pure focus policy allows main owned modal and media gallery when focused`() {
        assertTrue(evaluateDesktopMainChatContextFocused(isMainOrOwnedWindow = true, activeWindowFocused = true, activeWindowName = null))
        assertTrue(evaluateDesktopMainChatContextFocused(isMainOrOwnedWindow = false, activeWindowFocused = true, activeWindowName = MEDIA_GALLERY_WINDOW_NAME))
        assertFalse(evaluateDesktopMainChatContextFocused(isMainOrOwnedWindow = false, activeWindowFocused = true, activeWindowName = "other-window"))
        assertFalse(evaluateDesktopMainChatContextFocused(isMainOrOwnedWindow = false, activeWindowFocused = false, activeWindowName = MEDIA_GALLERY_WINDOW_NAME))
        assertFalse(evaluateDesktopMainChatContextFocused(isMainOrOwnedWindow = true, activeWindowFocused = false, activeWindowName = null))
    }

    @Test
    fun `main chat context treats focused main window owned modal and media gallery as active`() {
        if (GraphicsEnvironment.isHeadless()) return

        val main = object : Frame() {
            override fun isFocused(): Boolean = true
        }
        val ownedDialog = object : Dialog(main) {
            override fun isFocused(): Boolean = true
        }
        val galleryWindow = object : Frame() {
            override fun isFocused(): Boolean = true
        }.apply {
            name = MEDIA_GALLERY_WINDOW_NAME
        }
        val otherWindow = object : Frame() {
            override fun isFocused(): Boolean = true
        }.apply {
            name = "other-window"
        }
        val unfocusedGallery = object : Frame() {
            override fun isFocused(): Boolean = false
        }.apply {
            name = MEDIA_GALLERY_WINDOW_NAME
        }

        try {
            assertTrue(isDesktopMainChatContextFocused(main, main))
            assertTrue(isDesktopMainChatContextFocused(main, ownedDialog))
            assertTrue(isDesktopMainChatContextFocused(main, galleryWindow))
            assertFalse(isDesktopMainChatContextFocused(main, otherWindow))
            assertFalse(isDesktopMainChatContextFocused(main, unfocusedGallery))
            assertFalse(isDesktopMainChatContextFocused(main, null))

            assertTrue(isDesktopAppAnyWindowActive(otherWindow))
            assertFalse(isDesktopAppAnyWindowActive(unfocusedGallery))
            assertFalse(isDesktopAppAnyWindowActive(null))
        } finally {
            unfocusedGallery.dispose()
            otherWindow.dispose()
            galleryWindow.dispose()
            ownedDialog.dispose()
            main.dispose()
        }
    }
}
