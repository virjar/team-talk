package com.virjar.tk.ui.component.rich

/**
 * 文档预览的结构资源预算。
 *
 * 文档正文仍可达到服务端允许的总长度；这里只限制会放大 Compose 节点数量或递归深度的结构。
 * 超限块由预览层局部显示源码，不影响同一文档中其他块。
 */
internal object DocumentMarkdownPreviewBudget {
    const val MAX_QUOTE_DEPTH = 64
    const val MAX_QUOTE_SOURCE_WORK = 4_000_000L
    const val MAX_EXPANDED_QUOTE_NODES = 4_096
    const val MAX_TABLE_COLUMNS = 32
    const val MAX_TABLE_CELLS = 1_000

    fun canRenderQuoteAtDepth(depth: Int): Boolean = depth in 0 until MAX_QUOTE_DEPTH

    /**
     * Each visual quote level reparses the Markdown inside its outer marker. Charge the complete
     * source length at every level so deeply nested, near-limit documents cannot turn one megabyte
     * of stored text into dozens of megabytes of parser and substring work.
     */
    fun nextQuoteSourceWork(
        currentWork: Long,
        sourceLength: Int,
        sourceWorkLimit: Long = MAX_QUOTE_SOURCE_WORK,
    ): Long? {
        if (currentWork < 0L || sourceLength < 0 || sourceWorkLimit < 0L || currentWork > sourceWorkLimit) {
            return null
        }
        val remaining = sourceWorkLimit - currentWork
        if (sourceLength.toLong() > remaining) return null
        return currentWork + sourceLength
    }

    fun tableViolation(block: DocumentGfmTableBlock): TableViolation? {
        val columns = maxOf(
            1,
            block.headers.size,
            block.alignments.size,
            block.rows.maxOfOrNull(List<String>::size) ?: 0,
        )
        if (columns > MAX_TABLE_COLUMNS) return TableViolation.TOO_MANY_COLUMNS

        // 预览会把短行补齐为矩形；按实际 Compose 单元格数量计费，避免稀疏行绕过预算。
        val renderedCells = columns.toLong() * (block.rows.size.toLong() + 1L)
        return if (renderedCells > MAX_TABLE_CELLS) TableViolation.TOO_MANY_CELLS else null
    }
}

/** Immutable preview projection; quote children are null when that branch must stay local source. */
internal sealed interface DocumentMarkdownPreviewNode {
    val block: DocumentMarkdownBlock

    data class Leaf(
        override val block: DocumentMarkdownBlock,
    ) : DocumentMarkdownPreviewNode

    data class Quote(
        override val block: DocumentQuoteBlock,
        val children: List<DocumentMarkdownPreviewNode>?,
    ) : DocumentMarkdownPreviewNode
}

/**
 * Parse quote subtrees exactly once while sharing one consumable budget across all siblings.
 * A path-local immutable counter is insufficient because thousands of shallow sibling branches can
 * otherwise each receive the full allowance and collectively create an unbounded Compose tree.
 */
internal object DocumentMarkdownPreviewPlanner {
    fun plan(
        blocks: List<DocumentMarkdownBlock>,
        sourceWorkLimit: Long = DocumentMarkdownPreviewBudget.MAX_QUOTE_SOURCE_WORK,
        expandedQuoteNodeLimit: Int = DocumentMarkdownPreviewBudget.MAX_EXPANDED_QUOTE_NODES,
    ): List<DocumentMarkdownPreviewNode> {
        val budget = QuoteExpansionBudget(
            sourceWorkLimit = sourceWorkLimit,
            expandedQuoteNodeLimit = expandedQuoteNodeLimit,
        )
        return blocks.map { block -> planBlock(block, quoteDepth = 0, budget = budget) }
    }

    private fun planBlock(
        block: DocumentMarkdownBlock,
        quoteDepth: Int,
        budget: QuoteExpansionBudget,
    ): DocumentMarkdownPreviewNode {
        if (block !is DocumentQuoteBlock) return DocumentMarkdownPreviewNode.Leaf(block)
        if (
            !DocumentMarkdownPreviewBudget.canRenderQuoteAtDepth(quoteDepth) ||
            !budget.tryExpand(block.innerMarkdown.length)
        ) {
            return DocumentMarkdownPreviewNode.Quote(block, children = null)
        }

        val nestedBlocks = DocumentMarkdownBlockCodec.parse(block.innerMarkdown)
        return DocumentMarkdownPreviewNode.Quote(
            block = block,
            children = nestedBlocks.map { nested ->
                planBlock(nested, quoteDepth = quoteDepth + 1, budget = budget)
            },
        )
    }

    private class QuoteExpansionBudget(
        private val sourceWorkLimit: Long,
        private val expandedQuoteNodeLimit: Int,
    ) {
        private var sourceWork = 0L
        private var expandedQuoteNodes = 0

        fun tryExpand(sourceLength: Int): Boolean {
            if (expandedQuoteNodeLimit < 0 || expandedQuoteNodes >= expandedQuoteNodeLimit) return false
            val nextSourceWork = DocumentMarkdownPreviewBudget.nextQuoteSourceWork(
                currentWork = sourceWork,
                sourceLength = sourceLength,
                sourceWorkLimit = sourceWorkLimit,
            ) ?: return false
            sourceWork = nextSourceWork
            expandedQuoteNodes++
            return true
        }
    }
}

internal enum class TableViolation {
    TOO_MANY_COLUMNS,
    TOO_MANY_CELLS,
}
