package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RichTextLinkTest {

    @Test
    fun `标准网络链接保持原样`() {
        assertEquals("https://im.virjar.com/docs?a=1", normalizeRichTextLink(" https://im.virjar.com/docs?a=1 "))
        assertEquals("https://im.virjar.com/a_(b)", normalizeRichTextLink("https://im.virjar.com/a_(b)"))
        assertEquals("http://127.0.0.1:8080", normalizeRichTextLink("http://127.0.0.1:8080"))
        assertEquals("mailto:test@example.com", normalizeRichTextLink("mailto:test@example.com"))
    }

    @Test
    fun `裸域名自动补全安全协议`() {
        assertEquals("https://im.virjar.com/docs", normalizeRichTextLink("im.virjar.com/docs"))
    }

    @Test
    fun `拒绝脚本协议与含空白地址`() {
        assertNull(normalizeRichTextLink("javascript:alert(1)"))
        assertNull(normalizeRichTextLink("file:///tmp/a"))
        assertNull(normalizeRichTextLink("https://"))
        assertNull(normalizeRichTextLink("https://user:password@example.com"))
        assertNull(normalizeRichTextLink("user@example.com"))
        assertNull(normalizeRichTextLink("user@im.virjar.com/docs"))
        assertNull(normalizeRichTextLink("https://im.virjar.com/a b"))
        assertNull(normalizeRichTextLink("https://im.virjar.com/<unsafe>"))
        assertNull(normalizeRichTextLink("https://im.virjar.com/a\\b"))
        assertNull(normalizeRichTextLink("localhost"))
    }
}
