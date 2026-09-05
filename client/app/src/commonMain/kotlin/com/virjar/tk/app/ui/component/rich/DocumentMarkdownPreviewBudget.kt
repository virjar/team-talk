package com.virjar.tk.app.ui.component.rich

import com.virjar.tk.protocol.model.EmbeddedAsset

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
     * 每个可视引用层级都会重新解析其外层标记内的 Markdown。在每一层都按完整源码长度计费，
     * 这样深度嵌套、接近上限的文档就不会把 1 MB 的存储文本变成几十 MB 的解析和子串工作。
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

/** 不可变预览 projection；当该分支必须保留为本地源码时，引用子节点为 null。 */
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
 * 引用子树恰好解析一次，同时所有兄弟分支共享一份可消耗的预算。路径局部的不可变计数器
 * 不够用，否则成千上万个浅层兄弟分支可以各自获得全额配额，共同创建无界的 Compose 树。
 */
internal object DocumentMarkdownPreviewPlanner {
    fun plan(
        blocks: List<DocumentMarkdownBlock>,
        assets: List<EmbeddedAsset> = emptyList(),
        sourceWorkLimit: Long = DocumentMarkdownPreviewBudget.MAX_QUOTE_SOURCE_WORK,
        expandedQuoteNodeLimit: Int = DocumentMarkdownPreviewBudget.MAX_EXPANDED_QUOTE_NODES,
    ): List<DocumentMarkdownPreviewNode> {
        val budget = QuoteExpansionBudget(
            sourceWorkLimit = sourceWorkLimit,
            expandedQuoteNodeLimit = expandedQuoteNodeLimit,
        )
        return blocks.map { block -> planBlock(block, assets, quoteDepth = 0, budget = budget) }
    }

    private fun planBlock(
        block: DocumentMarkdownBlock,
        assets: List<EmbeddedAsset>,
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

        val nestedBlocks = DocumentMarkdownBlockCodec.parse(block.innerMarkdown, assets)
        return DocumentMarkdownPreviewNode.Quote(
            block = block,
            children = nestedBlocks.map { nested ->
                planBlock(nested, assets, quoteDepth = quoteDepth + 1, budget = budget)
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
