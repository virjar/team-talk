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
        // 折叠行数阈值随 0.0.7 放宽为 48：50 行折叠，办公常态的 30/40 行不折叠。
        val preview = assertNotNull(collapsedMessagePreview(buildRichTextBody("x<br>".repeat(50))))
        assertFalse(preview.contains("<br>"))
        assertNotNull(collapsedMessagePreview(buildRichTextBody("x\n".repeat(50))))
        // 办公常态消息不折叠：告警/小时报模板普遍在几十行以内。
        assertNull(collapsedMessagePreview(buildRichTextBody("x<br>".repeat(30))))
        assertNull(collapsedMessagePreview(buildRichTextBody("x\n".repeat(30))))
    }

    @Test fun officeLengthMessagesRemainCompleteWhilePastedLogsCollapse() {
        assertNull(collapsedMessagePreview(buildRichTextBody("字".repeat(2400))))
        assertNotNull(collapsedMessagePreview(buildRichTextBody("字".repeat(2401))))
    }

    @Test fun boundedPrefixDoesNotSplitEmojiSurrogatePair() {
        val preview = assertNotNull(collapsedMessagePreview(buildRichTextBody("a".repeat(239) + "😀" + "b".repeat(2400))))
        assertFalse(preview.any { it.isSurrogate() })
    }
}
