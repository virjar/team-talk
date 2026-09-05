package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.PacketBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkdownAssetPolicyTest {
    private val imageId = "00000000-0000-4000-8000-000000000101"
    private val fileId = "00000000-0000-4000-8000-000000000102"
    private val image = EmbeddedAsset(
        assetId = imageId,
        attachment = Attachment("owner/image.png", "架构图.png", "image/png", 12),
        thumbnail = Attachment("owner/thumb.jpg", "thumb.jpg", "image/jpeg", 4),
        width = 640,
        height = 480,
    )
    private val file = EmbeddedAsset(
        assetId = fileId,
        attachment = Attachment("owner/spec.pdf", "需求说明.pdf", "application/pdf", 34),
    )

    @Test
    fun `manifest is canonicalized by semantic markdown order and searchable labels`() {
        val markdown = "![系统架构](${EmbeddedAsset.uri(imageId)})\n[需求附件](${EmbeddedAsset.uri(fileId)})"
        val body = buildRichTextBody(markdown, listOf(file, image))

        assertEquals(listOf(imageId, fileId), body.assets.map(EmbeddedAsset::assetId))
        assertTrue("系统架构" in body.plainText)
        assertTrue("需求附件" in body.plainText)
        assertTrue("teamtalk-asset" !in body.plainText)
        assertTrue("owner/" !in body.plainText)
    }

    @Test
    fun `literal inline and fenced code never acquires asset semantics`() {
        val uri = EmbeddedAsset.uri(imageId)
        val markdown = "`![literal]($uri)`\n```md\n![fenced]($uri)\n```"
        assertEquals(emptyList(), MarkdownAssetPolicy.references(markdown))
        assertEquals(emptyList(), buildRichTextBody(markdown).assets)
    }

    @Test
    fun `inline code may cross lines and malformed fence closer does not end literal scope`() {
        val uri = EmbeddedAsset.uri(imageId)
        val inline = "before `line one\n![literal]($uri)\nline three` after"
        assertEquals(emptyList(), MarkdownAssetPolicy.references(inline))

        val fenced = """```md
![first literal]($uri)
``` trailing text is not a closer
![second literal]($uri)
```
![semantic]($uri)"""
        val references = MarkdownAssetPolicy.references(fenced)
        assertEquals(1, references.size)
        assertEquals("semantic", references.single().label)
        assertEquals(listOf(imageId), buildRichTextBody(fenced, listOf(image)).assets.map { it.assetId })

        val crlfFence = "```md\r\n![literal]($uri)\r\n```\r\n![semantic]($uri)"
        assertEquals("semantic", MarkdownAssetPolicy.references(crlfFence).single().label)
    }

    @Test
    fun `escaped internal scheme is recognized before admission checks`() {
        val escapedUri = "teamtalk\\-asset\\://asset/$imageId"
        val markdown = "![escaped]($escapedUri)"
        assertEquals(imageId, MarkdownAssetPolicy.references(markdown).single().assetId)
        assertEquals(listOf(imageId), buildRichTextBody(markdown, listOf(image)).assets.map { it.assetId })

        assertFailsWith<IllegalArgumentException> { buildRichTextBody(markdown) }
        assertFailsWith<IllegalArgumentException> {
            buildRichTextBody("![case](TEAMTALK-ASSET://asset/$imageId)")
        }
    }

    @Test
    fun `malformed internal uri and dangling manifests fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            buildRichTextBody("![x](teamtalk-asset://asset/NOT-A-UUID)")
        }
        assertFailsWith<IllegalArgumentException> {
            buildRichTextBody("![x](${EmbeddedAsset.uri(imageId)})")
        }
        assertFailsWith<IllegalArgumentException> {
            buildRichTextBody("plain", listOf(image))
        }
    }

    @Test
    fun `duplicate manifest ids and non-image image references are rejected`() {
        val imageMarkdown = "![x](${EmbeddedAsset.uri(imageId)})"
        assertFailsWith<IllegalArgumentException> {
            buildRichTextBody(imageMarkdown, listOf(image, image))
        }
        assertFailsWith<IllegalArgumentException> {
            buildRichTextBody(
                "![x](${EmbeddedAsset.uri(fileId)})",
                listOf(file),
            )
        }
    }

    @Test
    fun `one manifest descriptor may back multiple markdown placements`() {
        val uri = EmbeddedAsset.uri(imageId)
        val markdown = "![overview]($uri) and again ![detail]($uri)"
        val body = buildRichTextBody(markdown, listOf(image))

        assertEquals(2, MarkdownAssetPolicy.references(markdown).size)
        assertEquals(listOf(imageId), body.assets.map { it.assetId })
        assertTrue("overview" in body.plainText)
        assertTrue("detail" in body.plainText)
    }

    @Test
    fun `recovery scan can find references rejected by the commit budget`() {
        val markdown = List(MarkdownAssetPolicy.MAX_ASSET_REFERENCES + 1) {
            "![photo](${EmbeddedAsset.uri(imageId)})"
        }.joinToString(" ")

        assertFailsWith<IllegalArgumentException> { MarkdownAssetPolicy.references(markdown) }
        assertEquals(
            MarkdownAssetPolicy.MAX_ASSET_REFERENCES + 1,
            MarkdownAssetPolicy.recoveryReferences(markdown).size,
        )
    }

    @Test
    fun `plain text derivation never rewrites asset-looking code literals`() {
        val uri = EmbeddedAsset.uri(imageId)
        val inlineLiteral = "![literal]($uri)"
        val inline = buildRichTextBody("`$inlineLiteral`")
        assertEquals(inlineLiteral, inline.plainText)

        val crossLineLiteral = "first\n![literal]($uri)\nlast"
        val crossLine = buildRichTextBody("`$crossLineLiteral`")
        assertEquals(crossLineLiteral, crossLine.plainText)
    }

    @Test
    fun `external images remain non-network markdown and require no asset manifest`() {
        val body = buildRichTextBody("![tracking](https://example.invalid/pixel.png)")
        assertEquals(emptyList(), body.assets)
        assertEquals("[图片]", body.plainText)
    }

    @Test
    fun `rich text wire round trip preserves canonical embedded descriptors`() {
        val markdown = "![系统架构](${EmbeddedAsset.uri(imageId)})"
        val original = buildRichTextBody(markdown, listOf(image))
        val buffer = PacketBuffer().also(original::writeTo)
        assertEquals(original, RichTextBody.readFrom(PacketBuffer(buffer.toByteArray())))
    }

    @Test
    fun `all embedded asset envelopes reject noncanonical values while encoding`() {
        val markdown = "![系统架构](${EmbeddedAsset.uri(imageId)})\n[需求](${EmbeddedAsset.uri(fileId)})"
        val wrongOrder = listOf(file, image)

        assertFailsWith<IllegalArgumentException> {
            RichTextBody(markdown, emptyList(), "系统架构 需求", wrongOrder)
                .writeTo(PacketBuffer())
        }
        assertFailsWith<IllegalArgumentException> {
            ReplyBody("message-1", "user-1", content = markdown, assets = wrongOrder)
                .writeTo(PacketBuffer())
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentContent(markdown, wrongOrder).writeTo(PacketBuffer())
        }
        assertFailsWith<IllegalArgumentException> {
            Document(
                documentId = "doc-1",
                spaceId = "space-1",
                title = "设计",
                markdown = markdown,
                createdBy = "u1",
                createdAt = 1,
                updatedBy = "u1",
                updatedAt = 1,
                assets = wrongOrder,
            ).writeTo(PacketBuffer())
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentRevision(
                documentId = "doc-1",
                revision = 1,
                title = "设计",
                markdown = markdown,
                editedBy = "u1",
                editedAt = 1,
                assets = wrongOrder,
            ).writeTo(PacketBuffer())
        }

        val noncanonical = image.copy(
            attachment = image.attachment.copy(path = "/api/v1/files/${image.attachment.path}"),
        )
        assertFailsWith<IllegalArgumentException> { noncanonical.writeTo(PacketBuffer()) }
    }

    @Test
    fun `reply manifest round trips while dangling references and edit references fail closed`() {
        val markdown = "![x](${EmbeddedAsset.uri(imageId)})"
        val reply = ReplyBody("message-1", "user-1", content = markdown, assets = listOf(image))
        val replyBuffer = PacketBuffer().also(reply::writeTo)
        assertEquals(reply, ReplyBody.readFrom(PacketBuffer(replyBuffer.toByteArray())))

        assertFailsWith<IllegalArgumentException> {
            ReplyBody("message-1", "user-1", content = markdown).writeTo(PacketBuffer())
        }
        assertFailsWith<IllegalArgumentException> {
            EditBody("message-1", markdown).writeTo(PacketBuffer())
        }

        val replyWire = PacketBuffer().apply {
            writeString("message-1")
            writeString("user-1")
            writeString(null)
            writeString(null)
            writeString(markdown)
            writeVarInt(0)
        }
        assertFailsWith<IllegalArgumentException> {
            ReplyBody.readFrom(PacketBuffer(replyWire.toByteArray()))
        }
        val editWire = PacketBuffer().apply {
            writeString("message-1")
            writeString(markdown)
        }
        assertFailsWith<IllegalArgumentException> {
            EditBody.readFrom(PacketBuffer(editWire.toByteArray()))
        }

        // 字面示例不会产生资源语义，仍然合法。
        ReplyBody("message-1", "user-1", content = "`$markdown`").writeTo(PacketBuffer())
        EditBody("message-1", "`$markdown`").writeTo(PacketBuffer())
    }
}
