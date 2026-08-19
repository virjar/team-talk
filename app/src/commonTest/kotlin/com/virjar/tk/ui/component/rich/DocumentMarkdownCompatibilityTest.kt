package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentMarkdownCompatibilityTest {

    @Test
    fun `能力检查与文档 codec 共用管道预检而不进入 GFM 解析`() {
        val markdown = "x|".repeat(500_000)

        val capability = RichEditorMarkdownCapability.inspect(markdown)

        assertEquals(
            setOf(RichEditorUnsupportedMarkdownFeature.EXCESSIVE_STRUCTURE),
            capability.unsupportedFeatures,
        )
    }

    @Test
    fun `过深列表 AST 进入源码保护而不递归溢出`() {
        val markdown = buildString {
            repeat(MAX_MARKDOWN_AST_DEPTH + 8) { depth ->
                append("    ".repeat(depth))
                appendLine("- level-$depth")
            }
        }

        val capability = RichEditorMarkdownCapability.inspect(markdown)

        assertTrue(RichEditorUnsupportedMarkdownFeature.EXCESSIVE_NESTING in capability.unsupportedFeatures)
    }

    @Test
    fun `富文本编辑器已支持的基础语法不生成局部源码块`() {
        val markdown = """
            # 标题

            **粗体**、*斜体*、~~删除线~~、`inline code` 和 [链接](https://im.virjar.com)

            1. 第一项
            2. 第二项
                - 子项
        """.trimIndent()

        val result = RichEditorMarkdownCapability.inspect(markdown)

        assertFalse(result.requiresSourceMode)
        assertEquals(emptySet(), result.unsupportedFeatures)
    }

    @Test
    fun `识别不能无损往返的文档块结构`() {
        assertUnsupported("```kotlin\nval answer = 42\n```", RichEditorUnsupportedMarkdownFeature.FENCED_CODE_BLOCK)
        assertUnsupported("    val answer = 42", RichEditorUnsupportedMarkdownFeature.INDENTED_CODE_BLOCK)
        assertUnsupported("> 引用内容", RichEditorUnsupportedMarkdownFeature.BLOCK_QUOTE)
        assertUnsupported(
            "| 姓名 | 部门 |\n| --- | --- |\n| 张三 | 研发 |",
            RichEditorUnsupportedMarkdownFeature.TABLE,
        )
        assertUnsupported("- [x] 已完成", RichEditorUnsupportedMarkdownFeature.TASK_LIST)
    }

    @Test
    fun `识别图片和原始 HTML`() {
        assertUnsupported("![架构图](/files/diagram.png)", RichEditorUnsupportedMarkdownFeature.IMAGE)
        assertUnsupported("正文 <u>强调</u>", RichEditorUnsupportedMarkdownFeature.RAW_HTML)
        assertUnsupported("<section>\n块级内容\n</section>", RichEditorUnsupportedMarkdownFeature.RAW_HTML)
    }

    @Test
    fun `识别其他会被富文本模式规范化的 Markdown 结构`() {
        assertUnsupported("标题\n====", RichEditorUnsupportedMarkdownFeature.SETEXT_HEADING)
        assertUnsupported(
            "参见 [规范][spec]\n\n[spec]: https://im.virjar.com/spec",
            RichEditorUnsupportedMarkdownFeature.REFERENCE_LINK,
        )
        assertUnsupported("---", RichEditorUnsupportedMarkdownFeature.HORIZONTAL_RULE)
        assertUnsupported("第一行  \n第二行", RichEditorUnsupportedMarkdownFeature.HARD_LINE_BREAK)
    }

    @Test
    fun `只有非标准有序编号需要局部源码块`() {
        assertFalse(RichEditorMarkdownCapability.inspect("1. 一\n2. 二\n3. 三").requiresSourceMode)
        assertUnsupported("7. 七\n8. 八", RichEditorUnsupportedMarkdownFeature.NON_CANONICAL_ORDERED_LIST)
        assertUnsupported("1. 一\n3. 三", RichEditorUnsupportedMarkdownFeature.NON_CANONICAL_ORDERED_LIST)
    }

    @Test
    fun `链接扩展和多反引号代码必须进入局部源码块`() {
        assertUnsupported(
            "[文档](https://im.virjar.com \"内部说明\")",
            RichEditorUnsupportedMarkdownFeature.LINK_TITLE,
        )
        assertUnsupported(
            "[**重要**文档](https://im.virjar.com)",
            RichEditorUnsupportedMarkdownFeature.FORMATTED_LINK_LABEL,
        )
        assertUnsupported(
            "[`代码`](https://im.virjar.com)",
            RichEditorUnsupportedMarkdownFeature.FORMATTED_LINK_LABEL,
        )
        assertUnsupported(
            "``包含 ` 的代码``",
            RichEditorUnsupportedMarkdownFeature.MULTI_BACKTICK_CODE_SPAN,
        )
    }

    private fun assertUnsupported(
        markdown: String,
        feature: RichEditorUnsupportedMarkdownFeature,
    ) {
        val result = RichEditorMarkdownCapability.inspect(markdown)
        assertTrue(result.requiresSourceMode, "应使用局部源码块：$markdown")
        assertTrue(feature in result.unsupportedFeatures, "未识别 $feature：${result.unsupportedFeatures}")
    }
}
