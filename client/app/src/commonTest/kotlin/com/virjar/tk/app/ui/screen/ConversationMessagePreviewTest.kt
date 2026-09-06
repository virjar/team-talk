package com.virjar.tk.app.ui.screen

import com.virjar.tk.app.ui.component.MessagePreview
import com.virjar.tk.app.viewmodel.conversationMessagePreview
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationMessagePreviewTest {
    @Test
    fun `old clipboard-only projections have readable fallback without rewriting ordinary sentences or files`() {
        val internalName = "teamtalk-clipboard-142792303123456789.png"
        assertEquals("[图片]", lastMessagePreview(internalName, MessageType.RICH_TEXT.code))
        assertEquals(
            "请打开 $internalName",
            lastMessagePreview("请打开 $internalName", MessageType.RICH_TEXT.code),
        )
        assertEquals("[文件] $internalName", lastMessagePreview(internalName, MessageType.FILE.code))
        assertEquals("photo.png", lastMessagePreview("photo.png", MessageType.RICH_TEXT.code))
        assertEquals("teamtalk-clipboard-1.png", lastMessagePreview("teamtalk-clipboard-1.png", MessageType.RICH_TEXT.code))
    }

    @Test
    fun `old rich text bodies rebuild image previews from canonical source`() {
        val image = EmbeddedAsset(
            assetId = "11111111-1111-4111-8111-111111111111",
            attachment = Attachment("owner/image.png", "teamtalk-clipboard-142792303.png", "image/png", 12),
        )
        val body = buildRichTextBody(
            "![${image.attachment.name}](${EmbeddedAsset.uri(image.assetId)}) 这是正文",
            listOf(image),
        ).copy(plainText = "${image.attachment.name} 这是正文")
        assertEquals("[图片] 这是正文", MessagePreview.previewBody(body))
        val conversation = Conversation(chatId = "chat-1", chatType = 1, lastSeq = 12, lastMessageType = MessageType.RICH_TEXT.code)
        val message = Message(
            chatId = conversation.chatId,
            clientMsgId = "message-1",
            serverSeq = 12,
            senderUid = "sender-1",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1,
            body = body,
        )
        assertEquals("[图片] 这是正文", conversationMessagePreview(conversation, message))
        assertNull(conversationMessagePreview(conversation, message.copy(serverSeq = 13)))
        assertNull(conversationMessagePreview(conversation, message.copy(serverSeq = 0)))
        val literal = "teamtalk-clipboard-142792303123456789.png"
        assertEquals(literal, conversationMessagePreview(conversation, message.copy(body = buildRichTextBody(literal))))
    }
}
