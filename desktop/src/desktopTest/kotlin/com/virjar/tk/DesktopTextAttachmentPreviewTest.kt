package com.virjar.tk

import com.virjar.tk.model.Attachment
import com.virjar.tk.ui.component.TextAttachmentPreviewState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopTextAttachmentPreviewTest {

    @Test
    fun `bounded cache read stops after limit sentinel byte`() {
        val file = Files.createTempFile("teamtalk-preview", ".txt").toFile()
        try {
            file.writeBytes(ByteArray(64) { it.toByte() })

            val bytes = readDesktopTextAttachmentPreviewBytes(file, maxBytes = 8)

            assertEquals(9, bytes.size)
            assertEquals((0..8).map { it.toByte() }, bytes.toList())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `desktop dialog maps unsafe metadata without loading file into memory`() {
        assertIs<TextAttachmentPreviewState.TooLarge>(
            desktopTextAttachmentPreviewState(
                attachment(size = 600L * 1024L, contentType = "text/markdown"),
            ),
        )
        assertIs<TextAttachmentPreviewState.UnsupportedCharset>(
            desktopTextAttachmentPreviewState(
                attachment(size = 10L, contentType = "text/plain; charset=gbk"),
            ),
        )
    }

    @Test
    fun `controller intercepts supported text and leaves other files external`() {
        assertEquals(
            DesktopAttachmentOpenTarget.PREVIEW,
            desktopAttachmentOpenTarget(attachment(10L, "text/plain"), previewEnabled = true),
        )
        assertEquals(
            DesktopAttachmentOpenTarget.EXTERNAL,
            desktopAttachmentOpenTarget(attachment(10L, "application/pdf"), previewEnabled = true),
        )
        assertEquals(
            DesktopAttachmentOpenTarget.EXTERNAL,
            desktopAttachmentOpenTarget(attachment(10L, "text/markdown"), previewEnabled = false),
        )
    }

    private fun attachment(size: Long, contentType: String) = Attachment(
        path = "owner/readme.md",
        name = "readme.md",
        contentType = contentType,
        size = size,
    )
}
