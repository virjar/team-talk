package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.body.buildRichTextBody
import kotlin.test.*

class CollapsedMessagePreviewTest {
    @Test fun ordinaryMessagesRemainCompleteWhileLongLogsAreBounded() {
        assertNull(collapsedMessagePreview(buildRichTextBody("正常消息\n第二行")))
        val source = "```log\n" + (1..10_000).joinToString("\n") { "line-$it INFO detailed log record" } + "\n```"
        val body = buildRichTextBody(source)
        val preview = assertNotNull(collapsedMessagePreview(body))
        assertTrue(preview.length <= 245)
        assertTrue(preview.contains("line-1"))
        assertFalse(preview.contains("line-10000"))
        assertEquals(source, body.markdown)
    }

    @Test fun shortButTallMessagesCollapseAndDoNotLeakHtmlBreaks() {
        val preview = assertNotNull(collapsedMessagePreview(buildRichTextBody("x<br>".repeat(15))))
        assertFalse(preview.contains("<br>"))
        assertNotNull(collapsedMessagePreview(buildRichTextBody("x\n".repeat(13))))
        assertNull(collapsedMessagePreview(buildRichTextBody("x\n".repeat(3))))
    }

    @Test fun boundedPrefixDoesNotSplitEmojiSurrogatePair() {
        val preview = assertNotNull(collapsedMessagePreview(buildRichTextBody("a".repeat(239) + "😀" + "b".repeat(600))))
        assertFalse(preview.any { it.isSurrogate() })
    }
}
