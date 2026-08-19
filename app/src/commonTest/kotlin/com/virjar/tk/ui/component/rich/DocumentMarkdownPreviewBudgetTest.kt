package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DocumentMarkdownPreviewBudgetTest {

    @Test
    fun `引用只允许递归渲染六十四层`() {
        assertEquals(true, DocumentMarkdownPreviewBudget.canRenderQuoteAtDepth(0))
        assertEquals(true, DocumentMarkdownPreviewBudget.canRenderQuoteAtDepth(63))
        assertEquals(false, DocumentMarkdownPreviewBudget.canRenderQuoteAtDepth(64))
        assertEquals(false, DocumentMarkdownPreviewBudget.canRenderQuoteAtDepth(Int.MAX_VALUE))
    }

    @Test
    fun `引用累计源码工作量到达边界后局部降级`() {
        val almostAll = DocumentMarkdownPreviewBudget.MAX_QUOTE_SOURCE_WORK - 7L

        assertEquals(
            DocumentMarkdownPreviewBudget.MAX_QUOTE_SOURCE_WORK,
            DocumentMarkdownPreviewBudget.nextQuoteSourceWork(almostAll, 7),
        )
        assertNull(DocumentMarkdownPreviewBudget.nextQuoteSourceWork(almostAll, 8))
        assertNull(DocumentMarkdownPreviewBudget.nextQuoteSourceWork(Long.MAX_VALUE, 1))
    }

    @Test
    fun `引用规划器在兄弟分支间共享源码工作量预算`() {
        val blocks = List(3) { index ->
            DocumentQuoteBlock(key = "quote-$index", innerMarkdown = "1234")
        }

        val plan = DocumentMarkdownPreviewPlanner.plan(
            blocks = blocks,
            sourceWorkLimit = 8L,
            expandedQuoteNodeLimit = 100,
        ).map { it as DocumentMarkdownPreviewNode.Quote }

        assertNotNull(plan[0].children)
        assertNotNull(plan[1].children)
        assertNull(plan[2].children)
        assertEquals("1234", plan[2].block.innerMarkdown)
    }

    @Test
    fun `引用规划器在兄弟分支间共享展开节点预算`() {
        val blocks = List(3) { index ->
            DocumentQuoteBlock(key = "quote-$index", innerMarkdown = "x")
        }

        val plan = DocumentMarkdownPreviewPlanner.plan(
            blocks = blocks,
            sourceWorkLimit = 100L,
            expandedQuoteNodeLimit = 2,
        ).map { it as DocumentMarkdownPreviewNode.Quote }

        assertNotNull(plan[0].children)
        assertNotNull(plan[1].children)
        assertNull(plan[2].children)
    }

    @Test
    fun `三十二列表格在一千单元格内可以渲染`() {
        val table = table(columns = 32, renderedRows = 31)

        assertNull(DocumentMarkdownPreviewBudget.tableViolation(table))
    }

    @Test
    fun `表头对齐和正文任一超过三十二列都局部降级`() {
        assertEquals(
            TableViolation.TOO_MANY_COLUMNS,
            DocumentMarkdownPreviewBudget.tableViolation(table(columns = 33, renderedRows = 1)),
        )
        val oversizedAlignment = table(columns = 2, renderedRows = 1).copy(
            alignments = List(33) { DocumentTableAlignment.NONE },
        )
        assertEquals(
            TableViolation.TOO_MANY_COLUMNS,
            DocumentMarkdownPreviewBudget.tableViolation(oversizedAlignment),
        )
    }

    @Test
    fun `预览按补齐后的矩形单元格计费`() {
        val table = DocumentGfmTableBlock(
            key = "sparse",
            headers = List(32) { "h$it" },
            rows = List(31) { listOf("only-one-cell") },
        )

        assertEquals(
            TableViolation.TOO_MANY_CELLS,
            DocumentMarkdownPreviewBudget.tableViolation(table),
        )
    }

    private fun table(columns: Int, renderedRows: Int): DocumentGfmTableBlock =
        DocumentGfmTableBlock(
            key = "${columns}x$renderedRows",
            headers = List(columns) { "h$it" },
            rows = List((renderedRows - 1).coerceAtLeast(0)) { row ->
                List(columns) { column -> "$row:$column" }
            },
        )
}
