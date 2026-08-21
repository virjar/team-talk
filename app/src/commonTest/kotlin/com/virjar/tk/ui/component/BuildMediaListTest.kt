package com.virjar.tk.ui.component

import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.VideoBody
import com.virjar.tk.model.Message
import com.virjar.tk.body.MessageBody
import com.virjar.tk.model.Attachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildMediaListTest {

    private fun msg(
        body: MessageBody,
        sendStatus: Int = Message.SEND_STATUS_SENT,
    ) = Message(
        chatId = "c1", clientMsgId = "m1", senderUid = "u1",
        messageType = MessageBodyPolicy.typeOf(body).code,
        timestamp = 0,
        body = body,
        sendStatus = sendStatus,
    )

    @Test
    fun filters_to_image_and_video_only() {
        val messages = listOf(
            msg(buildRichTextBody("hello")),
            msg(ImageBody(attachment("image/one.png"))),
            msg(VideoBody(attachment("video/one.mp4"))),
            msg(buildRichTextBody("world")),
        )
        val result = buildMediaList(messages)
        assertEquals(2, result.size)
        assertEquals(GalleryMediaType.IMAGE, result[0].type)
        assertEquals(GalleryMediaType.VIDEO, result[1].type)
    }

    @Test
    fun video_uses_main_attachment_not_thumbnail() {
        val messages = listOf(
            msg(VideoBody(attachment("video/main.mp4"), thumbnail = attachment("video/thumb.jpg"))),
        )
        val result = buildMediaList(messages)
        assertEquals(1, result.size)
        assertEquals("video/main.mp4", result[0].path)
        assertNotEquals("video/thumb.jpg", result[0].path)
    }

    @Test
    fun empty_messages_returns_empty_list() {
        assertTrue(buildMediaList(emptyList()).isEmpty())
    }

    @Test
    fun text_only_messages_returns_empty_list() {
        val messages = listOf(msg(buildRichTextBody("hello")))
        assertTrue(buildMediaList(messages).isEmpty())
    }

    @Test
    fun uploading_media_with_blank_or_early_paths_never_enters_gallery() {
        val messages = listOf(
            msg(ImageBody(attachment("")), Message.SEND_STATUS_UPLOADING),
            msg(VideoBody(attachment("video/not-authoritative-yet.mp4")), Message.SEND_STATUS_UPLOADING),
        )

        assertTrue(buildMediaList(messages).isEmpty())
    }

    @Test
    fun failed_media_with_blank_paths_never_enters_gallery() {
        val messages = listOf(
            msg(ImageBody(attachment("   ")), Message.SEND_STATUS_FAILED),
            msg(VideoBody(attachment("")), Message.SEND_STATUS_FAILED),
        )

        assertTrue(buildMediaList(messages).isEmpty())
    }

    private fun attachment(path: String) = Attachment(path, path.substringAfterLast('/'), "application/octet-stream", 1)
}
