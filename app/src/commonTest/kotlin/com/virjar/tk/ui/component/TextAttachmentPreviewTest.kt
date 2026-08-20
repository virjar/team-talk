package com.virjar.tk.ui.component

import com.virjar.tk.model.Attachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

class TextAttachmentPreviewTest {
    @Test
    fun `mime wins and generic mime falls back to final extension`() {
        assertEquals(
            TextAttachmentPreviewKind.PLAIN_TEXT,
            textAttachmentPreviewKind(attachment("README.md", "text/plain; charset=UTF-8")),
        )
        assertEquals(
            TextAttachmentPreviewKind.MARKDOWN,
            textAttachmentPreviewKind(attachment("README.MD?download=1", "application/octet-stream")),
        )
        assertNull(textAttachmentPreviewKind(attachment("note.txt.exe", "application/octet-stream")))
        assertNull(textAttachmentPreviewKind(attachment("README.md", "application/pdf")))
    }

    @Test
    fun `plan rejects oversized invalid and unsupported charset inputs`() {
        assertIs<TextAttachmentPreviewPlan.TooLarge>(
            textAttachmentPreviewPlan(attachment("a.md", "text/markdown", size = 513L), maxBytes = 512L),
        )
        assertIs<TextAttachmentPreviewPlan.InvalidSize>(
            textAttachmentPreviewPlan(attachment("a.txt", "text/plain", size = -1L)),
        )
        assertIs<TextAttachmentPreviewPlan.UnsupportedCharset>(
            textAttachmentPreviewPlan(attachment("a.txt", "text/plain; charset=gbk")),
        )
        assertIs<TextAttachmentPreviewPlan.UseExternalApplication>(
            textAttachmentPreviewPlan(attachment("a.pdf", "application/pdf")),
        )
    }

    @Test
    fun `strict decoder strips UTF8 BOM and preserves markdown`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "# 标题\n正文".encodeToByteArray()
        val result = decodeTextAttachmentPreview(bytes, TextAttachmentPreviewKind.MARKDOWN)
        assertEquals("# 标题\n正文", result.content)
        assertEquals(TextAttachmentPreviewKind.MARKDOWN, result.kind)
    }

    @Test
    fun `strict decoder rejects invalid UTF8 controls and oversized payload`() {
        assertFails {
            decodeTextAttachmentPreview(
                byteArrayOf(0xC3.toByte(), 0x28),
                TextAttachmentPreviewKind.PLAIN_TEXT,
            )
        }
        assertFails {
            decodeTextAttachmentPreview("a\u0000b".encodeToByteArray(), TextAttachmentPreviewKind.PLAIN_TEXT)
        }
        assertFails {
            decodeTextAttachmentPreview(ByteArray(3), TextAttachmentPreviewKind.PLAIN_TEXT, maxBytes = 2)
        }
    }

    private fun attachment(name: String, contentType: String, size: Long = 10L) = Attachment(
        path = "owner/$name",
        name = name,
        contentType = contentType,
        size = size,
    )
}
