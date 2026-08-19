package com.virjar.tk.client

import com.virjar.tk.body.FileBody
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutboundMessagePolicyTest {

    @Test
    fun `sdk canonicalizes body after attachment paths`() {
        val file = message(
            MessageType.FILE,
            FileBody(Attachment("https://im.example/api/v1/files/user/file.pdf", "file.pdf", "application/pdf", 7)),
        )
        assertEquals(
            "user/file.pdf",
            ((canonicalizeOutboundMessage(file).body as FileBody).attachment.path),
        )

        val rich = message(
            MessageType.RICH_TEXT,
            RichTextBody("**真实正文**", plainText = "伪造派生正文"),
        )
        assertEquals("真实正文", (canonicalizeOutboundMessage(rich).body as RichTextBody).plainText)
    }

    @Test
    fun `sdk rejects type body mismatch before sending`() {
        assertFailsWith<IllegalArgumentException> {
            canonicalizeOutboundMessage(
                message(
                    MessageType.FILE,
                    RichTextBody("正文", plainText = "正文"),
                ),
            )
        }
    }

    private fun message(type: MessageType, body: com.virjar.tk.body.MessageBody) = Message(
        chatId = "chat-1",
        clientMsgId = "client-1",
        senderUid = "sender-1",
        messageType = type.code,
        timestamp = 1,
        body = body,
    )
}
