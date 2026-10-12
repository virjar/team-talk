package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.body.buildRichTextBody
import kotlin.test.*

/**
 * 折叠预览的核心不变量：超长内容必须截成有界预览（气泡不为长日志构建完整布局）、
 * 截断不拆代理对、折叠不修改原消息。折叠阈值（字符数/行数）是显式产品参数，随办公
 * 常态调整，不在单测锚定；办公常态消息不折叠的验收归 UI 验收与内测反馈。
 */
class CollapsedMessagePreviewTest {
    @Test fun superLongLogsCollapseToABoundedPreviewWithoutTouchingTheSource() {
        assertNull(collapsedMessagePreview(buildRichTextBody("正常消息\n第二行")))
        val source = "```log\n" + (1..10_000).joinToString("\n") { "line-$it INFO detailed log record" } + "\n```"
        val body = buildRichTextBody(source)
        val preview = assertNotNull(collapsedMessagePreview(body))
        assertTrue(preview.length < source.length / 100, "预览必须远小于原文")
        assertTrue(preview.length < 1_000, "预览必须有界，防长内容撑爆气泡布局")
        assertTrue(preview.contains("line-1"))
        assertFalse(preview.contains("line-10000"))
        assertEquals(source, body.markdown)
    }

    @Test fun boundedPrefixDoesNotSplitEmojiSurrogatePair() {
        val preview = assertNotNull(collapsedMessagePreview(buildRichTextBody("a".repeat(239) + "😀" + "b".repeat(10_000))))
        assertFalse(preview.any { it.isSurrogate() })
    }
}
