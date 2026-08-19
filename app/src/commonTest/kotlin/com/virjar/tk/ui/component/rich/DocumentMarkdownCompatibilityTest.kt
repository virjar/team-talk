package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentMarkdownCompatibilityTest {

    @Test
    fun `富文本编辑器已支持的基础语法不切换源码模式`() {
        val markdown = """
            # 标题

            **粗体**、*斜体*、~~删除线~~、`inline code` 和 [链接](https://im.virjar.com)

            1. 第一项
            2. 第二项
                - 子项
        """.trimIndent()

        val result = DocumentMarkdownCompatibility.inspect(markdown)

        assertFalse(result.requiresSourceMode)
        assertEquals(emptySet(), result.unsupportedFeatures)
    }

    @Test
    fun `识别不能无损往返的文档块结构`() {
        assertUnsupported("```kotlin\nval answer = 42\n```", DocumentMarkdownUnsupportedFeature.FENCED_CODE_BLOCK)
        assertUnsupported("    val answer = 42", DocumentMarkdownUnsupportedFeature.INDENTED_CODE_BLOCK)
        assertUnsupported("> 引用内容", DocumentMarkdownUnsupportedFeature.BLOCK_QUOTE)
        assertUnsupported(
            "| 姓名 | 部门 |\n| --- | --- |\n| 张三 | 研发 |",
            DocumentMarkdownUnsupportedFeature.TABLE,
        )
        assertUnsupported("- [x] 已完成", DocumentMarkdownUnsupportedFeature.TASK_LIST)
    }

    @Test
    fun `识别图片和原始 HTML`() {
        assertUnsupported("![架构图](/files/diagram.png)", DocumentMarkdownUnsupportedFeature.IMAGE)
        assertUnsupported("正文 <u>强调</u>", DocumentMarkdownUnsupportedFeature.RAW_HTML)
        assertUnsupported("<section>\n块级内容\n</section>", DocumentMarkdownUnsupportedFeature.RAW_HTML)
    }

    @Test
    fun `识别其他会被富文本模式规范化的 Markdown 结构`() {
        assertUnsupported("标题\n====", DocumentMarkdownUnsupportedFeature.SETEXT_HEADING)
        assertUnsupported(
            "参见 [规范][spec]\n\n[spec]: https://im.virjar.com/spec",
            DocumentMarkdownUnsupportedFeature.REFERENCE_LINK,
        )
        assertUnsupported("---", DocumentMarkdownUnsupportedFeature.HORIZONTAL_RULE)
        assertUnsupported("第一行  \n第二行", DocumentMarkdownUnsupportedFeature.HARD_LINE_BREAK)
    }

    @Test
    fun `只有非标准有序编号需要源码模式`() {
        assertFalse(DocumentMarkdownCompatibility.inspect("1. 一\n2. 二\n3. 三").requiresSourceMode)
        assertUnsupported("7. 七\n8. 八", DocumentMarkdownUnsupportedFeature.NON_CANONICAL_ORDERED_LIST)
        assertUnsupported("1. 一\n3. 三", DocumentMarkdownUnsupportedFeature.NON_CANONICAL_ORDERED_LIST)
    }

    @Test
    fun `链接扩展和多反引号代码必须进入源码模式`() {
        assertUnsupported(
            "[文档](https://im.virjar.com \"内部说明\")",
            DocumentMarkdownUnsupportedFeature.LINK_TITLE,
        )
        assertUnsupported(
            "[**重要**文档](https://im.virjar.com)",
            DocumentMarkdownUnsupportedFeature.FORMATTED_LINK_LABEL,
        )
        assertUnsupported(
            "[`代码`](https://im.virjar.com)",
            DocumentMarkdownUnsupportedFeature.FORMATTED_LINK_LABEL,
        )
        assertUnsupported(
            "``包含 ` 的代码``",
            DocumentMarkdownUnsupportedFeature.MULTI_BACKTICK_CODE_SPAN,
        )
    }

    private fun assertUnsupported(
        markdown: String,
        feature: DocumentMarkdownUnsupportedFeature,
    ) {
        val result = DocumentMarkdownCompatibility.inspect(markdown)
        assertTrue(result.requiresSourceMode, "应为源码模式：$markdown")
        assertTrue(feature in result.unsupportedFeatures, "未识别 $feature：${result.unsupportedFeatures}")
    }
}
