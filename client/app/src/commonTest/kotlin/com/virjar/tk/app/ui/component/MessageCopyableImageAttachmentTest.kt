package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 复制动作取图规则：独立图片消息与"仅一张内嵌图片"的富文本都取图本体，图文混排回落文本。 */
class MessageCopyableImageAttachmentTest {
    private val imageAttachment = Attachment(
        path = "files/a.png",
        name = "clipboard.png",
        contentType = "image/png",
        size = 8,
    )
    private val asset = EmbeddedAsset(
        assetId = "11111111-1111-1111-1111-111111111111",
        attachment = imageAttachment,
    )
    private val imageMarkdown = "![剪贴板图片.png](teamtalk-asset://asset/11111111-1111-1111-1111-111111111111)"

    private fun message(body: com.virjar.tk.protocol.body.MessageBody) = Message(
        chatId = "c",
        clientMsgId = "m1",
        senderUid = "u1",
        messageType = 0,
        timestamp = 0,
        body = body,
    )

    @Test
    fun `standalone image message resolves its attachment`() {
        assertEquals(imageAttachment, messageCopyableImageAttachment(message(ImageBody(attachment = imageAttachment))))
    }

    @Test
    fun `rich text holding exactly one image and no visible text resolves the image`() {
        val richText = RichTextBody(
            markdown = "$imageMarkdown\n",
            plainText = "剪贴板图片.png",
            assets = listOf(asset),
        )
        assertEquals(imageAttachment, messageCopyableImageAttachment(message(richText)))
    }

    @Test
    fun `rich text with visible text beside the image falls back to text copy`() {
        val richText = RichTextBody(
            markdown = "看这张 $imageMarkdown",
            plainText = "看这张 剪贴板图片.png",
            assets = listOf(asset),
        )
        assertNull(messageCopyableImageAttachment(message(richText)))
    }

    @Test
    fun `rich text with two images falls back to text copy`() {
        val second = EmbeddedAsset(
            assetId = "22222222-2222-2222-2222-222222222222",
            attachment = imageAttachment,
        )
        val secondMarkdown = "![b.png](teamtalk-asset://asset/22222222-2222-2222-2222-222222222222)"
        val richText = RichTextBody(
            markdown = "$imageMarkdown\n$secondMarkdown",
            plainText = "剪贴板图片.png b.png",
            assets = listOf(asset, second),
        )
        assertNull(messageCopyableImageAttachment(message(richText)))
    }
}
