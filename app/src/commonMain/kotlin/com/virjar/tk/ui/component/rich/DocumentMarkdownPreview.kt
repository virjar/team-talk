package com.virjar.tk.ui.component.rich

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentMarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    onUrlClick: (String) -> Unit = {},
) {
    val previewNodes = remember(markdown) {
        DocumentMarkdownPreviewPlanner.plan(DocumentMarkdownBlockCodec.parse(markdown))
    }
    LazyColumn(
        modifier = modifier.testTag("documents.editor.preview.blocks"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(previewNodes, key = { _, node -> node.block.key }) { _, node ->
            Box(Modifier.fillMaxWidth().widthIn(max = 920.dp)) {
                DocumentMarkdownBlockPreview(node = node, onUrlClick = onUrlClick)
            }
        }
    }
}

@Composable
private fun DocumentMarkdownBlockPreview(
    node: DocumentMarkdownPreviewNode,
    onUrlClick: (String) -> Unit,
) {
    val block = node.block
    when (block) {
        is DocumentRichRun -> MarkdownText(
            content = block.markdown,
            modifier = Modifier.fillMaxWidth(),
            onUrlClick = onUrlClick,
        )
        is DocumentQuoteBlock -> {
            val quote = node as DocumentMarkdownPreviewNode.Quote
            val children = quote.children
            if (children == null) {
                DocumentSourcePreview(
                    title = "引用内容超过预览资源上限，已显示源码",
                    source = block.localPreviewSource(),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp)) {
                        Spacer(
                            Modifier.width(4.dp).fillMaxHeight().heightIn(min = 44.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            children.forEach { nested ->
                                DocumentMarkdownBlockPreview(
                                    node = nested,
                                    onUrlClick = onUrlClick,
                                )
                            }
                        }
                    }
                }
            }
        }
        is DocumentCodeFenceBlock -> DocumentCodePreview(block)
        is DocumentGfmTableBlock -> when (DocumentMarkdownPreviewBudget.tableViolation(block)) {
            null -> DocumentTablePreview(block, onUrlClick)
            TableViolation.TOO_MANY_COLUMNS -> DocumentSourcePreview(
                title = "表格超过 ${DocumentMarkdownPreviewBudget.MAX_TABLE_COLUMNS} 列，已显示源码",
                source = block.localPreviewSource(),
            )
            TableViolation.TOO_MANY_CELLS -> DocumentSourcePreview(
                title = "表格超过 ${DocumentMarkdownPreviewBudget.MAX_TABLE_CELLS} 个单元格，已显示源码",
                source = block.localPreviewSource(),
            )
        }
        is DocumentOpaqueRawBlock -> DocumentRawPreview(block)
    }
}

@Composable
private fun DocumentCodePreview(block: DocumentCodeFenceBlock) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (!block.infoString.isNullOrBlank() || !block.language.isNullOrBlank()) Text(
                block.infoString ?: block.language.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            SelectionContainer {
                Text(
                    block.code,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun DocumentTablePreview(block: DocumentGfmTableBlock, onUrlClick: (String) -> Unit) {
    val columnCount = maxOf(
        1,
        block.headers.size,
        block.alignments.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    val headers = block.headers + List((columnCount - block.headers.size).coerceAtLeast(0)) { "" }
    val alignments = block.alignments +
        List((columnCount - block.alignments.size).coerceAtLeast(0)) { DocumentTableAlignment.NONE }
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
            DocumentTablePreviewRow(headers, alignments, header = true, onUrlClick = onUrlClick)
            block.rows.forEach { row ->
                DocumentTablePreviewRow(
                    row + List((columnCount - row.size).coerceAtLeast(0)) { "" },
                    alignments = alignments,
                    header = false,
                    onUrlClick = onUrlClick,
                )
            }
        }
    }
}

@Composable
private fun DocumentTablePreviewRow(
    cells: List<String>,
    alignments: List<DocumentTableAlignment>,
    header: Boolean,
    onUrlClick: (String) -> Unit,
) {
    Row {
        cells.forEachIndexed { index, value ->
            Surface(
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                color = if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(180.dp).heightIn(min = 46.dp),
            ) {
                val contentAlignment = when (alignments.getOrNull(index)) {
                    DocumentTableAlignment.CENTER -> Alignment.Center
                    DocumentTableAlignment.RIGHT -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    contentAlignment = contentAlignment,
                ) {
                    MarkdownText(
                        content = decodeDocumentTableCellForVisual(value),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        onUrlClick = onUrlClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentRawPreview(block: DocumentOpaqueRawBlock) {
    DocumentSourcePreview(
        title = "暂未建模的 Markdown 扩展块",
        source = block.rawMarkdown,
    )
}

@Composable
private fun DocumentSourcePreview(title: String, source: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    source,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

/** 仅取当前块正文，避免把相邻块之间的 leading/trailing 分隔符带进降级卡片。 */
private fun DocumentMarkdownBlock.localPreviewSource(): String {
    if (!dirty && originalMarkdown.isNotEmpty()) {
        val start = leadingMarkdown.length.coerceAtMost(originalMarkdown.length)
        val end = (originalMarkdown.length - trailingMarkdown.length).coerceIn(start, originalMarkdown.length)
        return originalMarkdown.substring(start, end)
    }
    val encoded = DocumentMarkdownBlockCodec.encode(listOf(this))
    val start = leadingMarkdown.length.coerceAtMost(encoded.length)
    val end = (encoded.length - trailingMarkdown.length).coerceIn(start, encoded.length)
    return encoded.substring(start, end)
}
