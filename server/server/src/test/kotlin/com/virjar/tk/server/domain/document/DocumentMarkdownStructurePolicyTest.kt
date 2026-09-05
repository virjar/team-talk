package com.virjar.tk.server.domain.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentMarkdownStructurePolicyTest {

    @Test
    fun `六十四层引用通过而六十五层被拒绝`() {
        validate("> ".repeat(64) + "正文")
        assertFailsWith<IllegalArgumentException> {
            validate("> ".repeat(65) + "正文")
        }
        assertFailsWith<IllegalArgumentException> {
            validate("- " + "> ".repeat(65) + "列表中的正文")
        }
        assertFailsWith<IllegalArgumentException> {
            validate("1. " + "> ".repeat(65) + "有序列表中的正文")
        }
    }

    @Test
    fun `围栏代码中的引用和表格符号不消耗结构预算`() {
        val markdown = buildString {
            appendLine("```")
            appendLine("> ".repeat(100) + "代码")
            repeat(40) { append('|').append(" cell ") }
            appendLine('|')
            appendLine("```")
            append("正文")
        }

        validate(markdown)
    }

    @Test
    fun `有序列表中的围栏按列表内容缩进关闭并恢复后续预算`() {
        val validFence = buildString {
            appendLine("100. ```text")
            appendLine("     " + "> ".repeat(100) + "代码字面量")
            appendLine("     | 代码 | 中的 | 管道 |")
            appendLine("     ```")
            append("正文")
        }
        validate(validFence)

        val excessiveAfterFence = validFence + "\n" + "> ".repeat(65) + "围栏后的引用"
        assertFailsWith<IllegalArgumentException> {
            validate(excessiveAfterFence)
        }
    }

    @Test
    fun `围栏所在容器中断时当前行重新进入结构预算`() {
        val markdown = buildString {
            appendLine("> ```text")
            appendLine("已离开引用容器")
            append("> ".repeat(65) + "后续引用")
        }

        assertFailsWith<IllegalArgumentException> { validate(markdown) }
    }

    @Test
    fun `三十三列表格被拒绝`() {
        val header = tableRow(33) { "h$it" }
        val delimiter = tableRow(33) { "---" }

        assertFailsWith<IllegalArgumentException> {
            validate("$header\n$delimiter")
        }
    }

    @Test
    fun `表格按补齐后的矩形单元格计费`() {
        val header = tableRow(32) { "h$it" }
        val delimiter = tableRow(32) { "---" }
        val sparseRows = List(31) { "| only-one-cell |" }

        assertFailsWith<IllegalArgumentException> {
            validate(listOf(header, delimiter).plus(sparseRows).joinToString("\n"))
        }
    }

    @Test
    fun `与客户端解析器一致接受单横线和转义管道正文行`() {
        val markdown = buildString {
            appendLine("| 标题 |")
            appendLine("| : - : |")
            repeat(DocumentService.MAX_MARKDOWN_TABLE_CELLS) {
                appendLine("only\\|escaped")
            }
        }

        assertFailsWith<IllegalArgumentException> { validate(markdown) }
    }

    @Test
    fun `一千单元格边界通过`() {
        val header = tableRow(10) { "h$it" }
        val delimiter = tableRow(10) { if (it % 2 == 0) ":---" else "---:" }
        val rows = List(99) { row -> tableRow(10) { column -> "$row:$column" } }

        validate(listOf(header, delimiter).plus(rows).joinToString("\n"))
    }

    @Test
    fun `普通含管道文本和不匹配分隔行不误判为表格`() {
        val markdown = """
            正文 a | b
            | --- | --- | --- |

            仍然是正文 | 不是表格
        """.trimIndent()

        validate(markdown)
    }

    @Test
    fun `可视块和物理行预算在建立AST前拒绝异常文档`() {
        validate(List(DocumentService.MAX_MARKDOWN_RENDERABLE_BLOCKS) { "正文 $it" }.joinToString("\n"))
        assertFailsWith<IllegalArgumentException> {
            validate(
                List(DocumentService.MAX_MARKDOWN_RENDERABLE_BLOCKS + 1) { "正文 $it" }
                    .joinToString("\n")
            )
        }

        assertFailsWith<IllegalArgumentException> {
            validate("\n".repeat(DocumentService.MAX_MARKDOWN_LINES))
        }
    }

    @Test
    fun `大型围栏正文只计一个可视块但仍受总行数约束`() {
        val markdown = buildString {
            appendLine("```text")
            repeat(DocumentService.MAX_MARKDOWN_RENDERABLE_BLOCKS + 1) { appendLine("code $it") }
            append("```")
        }

        validate(markdown)
    }

    @Test
    fun `百万字符普通管道行不物化单元格且不会被误判为表格`() {
        val markdown = "x|".repeat(DocumentService.MAX_MARKDOWN_LENGTH / 2)
        assertEquals(DocumentService.MAX_MARKDOWN_LENGTH, markdown.length)

        validate(markdown)
    }

    private fun validate(markdown: String) = DocumentMarkdownStructurePolicy.validate(
        markdown = markdown,
        maxQuoteDepth = DocumentService.MAX_MARKDOWN_QUOTE_DEPTH,
        maxTableColumns = DocumentService.MAX_MARKDOWN_TABLE_COLUMNS,
        maxTableCells = DocumentService.MAX_MARKDOWN_TABLE_CELLS,
        maxLines = DocumentService.MAX_MARKDOWN_LINES,
        maxRenderableBlocks = DocumentService.MAX_MARKDOWN_RENDERABLE_BLOCKS,
    )

    private fun tableRow(columns: Int, cell: (Int) -> String): String =
        "| " + (0 until columns).joinToString(" | ", transform = cell) + " |"
}
