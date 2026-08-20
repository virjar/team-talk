package com.virjar.tk

import com.virjar.tk.model.Attachment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidTextAttachmentPreviewTest {
    @Test
    fun `attachment preview route preserves untrusted metadata as opaque segments`() {
        val attachment = Attachment(
            path = "files/2026/研发 A+B%25/readme.md?raw=1",
            name = "说明 + 50%.md",
            contentType = "text/markdown; charset=utf-8",
            size = 42L,
        )

        val route = Routes.textAttachmentPreview(attachment)
        val segments = route.split('/')

        assertEquals("text_attachment_preview", segments[0])
        assertEquals(attachment.path, decodeAttachmentRouteValue(segments[1]))
        assertEquals(attachment.name, decodeAttachmentRouteValue(segments[2]))
        assertEquals(attachment.contentType, decodeAttachmentRouteValue(segments[3]))
        assertEquals(attachment.size, segments[4].toLong())
        assertFalse(route.contains(attachment.path), "附件路径不能以可解释的导航文本出现")
    }

    @Test
    fun `chat controller and preview page resolve the same authenticated cache target`() {
        val root = Files.createTempDirectory("teamtalk-text-preview-target").toFile()
        try {
            val namespace = mediaCacheNamespace("uid", "token", "nonce")
            val attachment = Attachment("files/readme.md", "../README.md", "text/markdown", 10L)
            val first = attachmentCacheFile(root, namespace, attachment)
            val second = attachmentCacheFile(root, namespace, attachment)
            val another = attachmentCacheFile(
                root,
                namespace,
                attachment.copy(path = "files/another.md"),
            )

            assertEquals(first.canonicalFile, second.canonicalFile)
            assertNotEquals(first.canonicalFile, another.canonicalFile)
            assertTrue(first.toPath().startsWith(root.toPath()))
            assertFalse(first.name.contains(".."))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `preview disk reader stops after exactly limit plus one byte`() {
        val file = Files.createTempFile("teamtalk-text-preview-limit", ".txt").toFile()
        try {
            file.writeBytes(ByteArray(128) { it.toByte() })

            val probe = readTextAttachmentPreviewBytes(file, maxBytes = 32L)

            assertEquals(33, probe.size)
            assertEquals(ByteArray(33) { it.toByte() }.toList(), probe.toList())
        } finally {
            file.delete()
        }
    }
}
