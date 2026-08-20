package com.virjar.tk.util

import com.virjar.tk.body.GenericPayload
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagePreviewTest {
    @Test
    fun `unknown generic message uses a safe placeholder without exposing opaque bytes`() {
        assertEquals(
            "不支持的扩展消息",
            MessagePreview.previewBody(GenericPayload(404, "secret opaque data".encodeToByteArray())),
        )
    }
}
