package com.virjar.tk.ui.component

import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.TextBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.model.Message
import com.virjar.tk.model.MessageBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildMediaListTest {

    private fun msg(body: MessageBody) = Message(
        chatId = "c1", clientMsgId = "m1", senderUid = "u1",
        messageType = 1, timestamp = 0, body = body,
    )

    @Test
    fun filters_to_image_and_video_only() {
        val messages = listOf(
            msg(TextBody("hello")),
            msg(ImageBody("http://img1")),
            msg(VideoBody("http://vid1")),
            msg(TextBody("world")),
        )
        val result = buildMediaList(messages)
        assertEquals(2, result.size)
        assertEquals("image", result[0].type)
        assertEquals("video", result[1].type)
    }

    @Test
    fun video_uses_url_not_thumbnailUrl() {
        val messages = listOf(
            msg(VideoBody(url = "http://video.mp4", thumbnailUrl = "http://thumb.jpg")),
        )
        val result = buildMediaList(messages)
        assertEquals(1, result.size)
        assertEquals("http://video.mp4", result[0].url)
        assertNotEquals("http://thumb.jpg", result[0].url)
    }

    @Test
    fun empty_messages_returns_empty_list() {
        assertTrue(buildMediaList(emptyList()).isEmpty())
    }

    @Test
    fun text_only_messages_returns_empty_list() {
        val messages = listOf(msg(TextBody("hello")))
        assertTrue(buildMediaList(messages).isEmpty())
    }
}
