package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import kotlin.test.*

class MediaClickHandlerTest {
    private val asset = EmbeddedAsset(
        "00000000-0000-4000-8000-000000000001",
        Attachment("files/shared-image", "image.png", "image/png", 42),
    )

    @Test
    fun coldFullTextKeepsItsExactEmbeddedImageAndExcludesAnotherChatWithTheSameMessageId() {
        val markdown = "![image](${EmbeddedAsset.URI_PREFIX}${asset.assetId})"
        val cold = message("cold").copy(
            messageType = 1,
            body = RichTextBody("$markdown\n$markdown", plainText = "image\nimage", assets = listOf(asset)),
        )
        val samePathForward = message("forward")
        val otherChat = message("cold").copy(chatId = "other-chat")
        val gallery = mediaGalleryForMessage(listOf(samePathForward, otherChat), cold)

        assertEquals(2, gallery.size)
        assertEquals(2, gallery.map(GalleryItem::stableId).distinct().size)
        val target = gallery.single { it.sourceMessageId == cold.clientMsgId }
        assertEquals(asset.assetId, target.sourceAssetId)
        assertEquals(asset.attachment, target.attachment)
    }

    @Test
    fun residentEditsRemainAuthoritativeAndOneMessageCannotBeDuplicatedByItsClickSnapshot() {
        val clicked = message("image")
        val edited = clicked.copy(body = ImageBody(asset.attachment.copy(path = "files/edited-image")))
        val gallery = mediaGalleryForMessage(listOf(edited, edited), clicked)
        assertEquals("files/edited-image", gallery.single().path)
        assertNull(gallery.single().sourceAssetId)
        assertEquals(clicked.clientMsgId, gallery.single().sourceMessageId)
    }

    @Test
    fun aResidentRevokeCannotFallBackToAnEarlierClickAndAColdRevokedRowStaysHidden() {
        val clicked = message("revoked")
        val revoked = clicked.copy(flags = Message.FLAG_REVOKED)
        assertTrue(mediaGalleryForMessage(listOf(revoked), clicked).isEmpty())
        assertTrue(mediaGalleryForMessage(emptyList(), revoked).isEmpty())
        assertEquals(clicked.clientMsgId, mediaGalleryForMessage(emptyList(), clicked).single().sourceMessageId)
    }

    private fun message(id: String) = Message(
        chatId = "chat", clientMsgId = id, serverSeq = 12L, senderUid = "sender",
        messageType = 2, timestamp = 1L, body = ImageBody(asset.attachment),
    )
}
