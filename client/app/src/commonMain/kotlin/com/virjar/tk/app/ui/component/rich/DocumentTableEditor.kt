package com.virjar.tk.app.ui.component.rich

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentTableBlockEditor(
    block: DocumentGfmTableBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onActivate: () -> Unit,
    onChange: (DocumentGfmTableBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var activeCell by remember(block.key) { mutableStateOf<DocumentTableCellAddress?>(null) }
    val editorState = DocumentTableEditorState(block = block, activeCell = activeCell)

    fun perform(action: DocumentTableEditAction) {
        val changed = editorState.perform(action)
        activeCell = changed.activeCell
        if (changed.block != block) onChange(changed.block)
    }

    val insertionLimitMessage = when {
        editorState.rowInsertLimit == DocumentTableInsertLimit.MAX_CELLS &&
            editorState.columnInsertLimit == DocumentTableInsertLimit.MAX_COLUMNS ->
            "已达上限：最多 ${DocumentMarkdownPreviewBudget.MAX_TABLE_COLUMNS} 列；新增行不得超过 " +
                "${DocumentMarkdownPreviewBudget.MAX_TABLE_CELLS} 个单元格（含表头）"
        editorState.rowInsertLimit == DocumentTableInsertLimit.MAX_CELLS &&
            editorState.columnInsertLimit == DocumentTableInsertLimit.MAX_CELLS ->
            "已达容量上限：最多 ${DocumentMarkdownPreviewBudget.MAX_TABLE_CELLS} 个单元格（含表头）"
        editorState.columnInsertLimit == DocumentTableInsertLimit.MAX_COLUMNS ->
            "已达 ${DocumentMarkdownPreviewBudget.MAX_TABLE_COLUMNS} 列上限"
        editorState.rowInsertLimit == DocumentTableInsertLimit.MAX_CELLS ->
            "新增行会超过 ${DocumentMarkdownPreviewBudget.MAX_TABLE_CELLS} 个单元格上限（含表头）"
        editorState.columnInsertLimit == DocumentTableInsertLimit.MAX_CELLS ->
            "新增列会超过 ${DocumentMarkdownPreviewBudget.MAX_TABLE_CELLS} 个单元格上限（含表头）"
        else -> null
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("documents.editor.table.${block.key}"),
    ) {
        Column {
            DocumentBlockHeader(
                icon = { Icon(Icons.Filled.TableChart, null) },
                label = "表格 · ${editorState.columnCount} 列",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                testTagPrefix = "documents.editor.table.${block.key}",
            )
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                DocumentTableEditorRow(
                    cells = editorState.normalizedHeaders,
                    alignments = editorState.normalizedAlignments,
                    header = true,
                    testTagPrefix = "documents.editor.table.header.${block.key}",
                    activeColumn = editorState.activeCell
                        ?.takeIf { it.isHeader }
                        ?.columnIndex,
                    onActivate = { column ->
                        activeCell = editorState.focusHeader(column).activeCell
                        onActivate()
                    },
                    onCellChange = { column, value ->
                        val headers = editorState.normalizedHeaders.toMutableList().apply { this[column] = value }
                        onChange(block.copy(headers = headers, dirty = true))
                    },
                    onAlignmentChange = { column, alignment ->
                        val alignments = editorState.normalizedAlignments.toMutableList().apply { this[column] = alignment }
                        onChange(block.copy(alignments = alignments, dirty = true))
                    },
                )
                editorState.normalizedRows.forEachIndexed { rowIndex, row ->
                    DocumentTableEditorRow(
                        cells = row,
                        alignments = editorState.normalizedAlignments,
                        header = false,
                        testTagPrefix = "documents.editor.table.row.$rowIndex.${block.key}",
                        activeColumn = editorState.activeCell
                            ?.takeIf { it.rowIndex == rowIndex }
                            ?.columnIndex,
                        onActivate = { column ->
                            activeCell = editorState.focusRow(rowIndex, column).activeCell
                            onActivate()
                        },
                        onCellChange = { column, value ->
                            val rows = editorState.normalizedRows.map { it.toMutableList() }.toMutableList()
                            rows[rowIndex][column] = value
                            onChange(block.copy(rows = rows, dirty = true))
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("documents.editor.table.actions.${block.key}"),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { perform(DocumentTableEditAction.INSERT_ROW_AFTER) },
                    enabled = editorState.canInsertRow,
                    modifier = Modifier.testTag("documents.editor.table.addRow.${block.key}"),
                ) {
                    Text(if (editorState.activeCell == null) "+ 末尾行" else "+ 下方行")
                }
                TextButton(
                    onClick = { perform(DocumentTableEditAction.INSERT_COLUMN_AFTER) },
                    enabled = editorState.canInsertColumn,
                    modifier = Modifier.testTag("documents.editor.table.addColumn.${block.key}"),
                ) {
                    Text(if (editorState.activeCell == null) "+ 末尾列" else "+ 右侧列")
                }
                if (block.rows.isNotEmpty()) TextButton(
                    onClick = { perform(DocumentTableEditAction.DELETE_CURRENT_ROW) },
                    enabled = editorState.canDeleteCurrentRow,
                    modifier = Modifier.testTag("documents.editor.table.removeRow.${block.key}"),
                ) { Text("删除当前行") }
                if (editorState.columnCount > 1) TextButton(
                    onClick = { perform(DocumentTableEditAction.DELETE_CURRENT_COLUMN) },
                    enabled = editorState.canDeleteCurrentColumn,
                    modifier = Modifier.testTag("documents.editor.table.removeColumn.${block.key}"),
                ) { Text("删除当前列") }
            }
            if (insertionLimitMessage != null) {
                Text(
                    text = insertionLimitMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                        .testTag("documents.editor.table.limit.${block.key}"),
                )
            }
        }
    }
}

/** [rowIndex] 为 null 表示表头单元格；数据行从 0 开始计数。 */
internal data class DocumentTableCellAddress(
    val rowIndex: Int?,
    val columnIndex: Int,
) {
    val isHeader: Boolean get() = rowIndex == null
}

internal enum class DocumentTableEditAction {
    INSERT_ROW_AFTER,
    INSERT_COLUMN_AFTER,
    DELETE_CURRENT_ROW,
    DELETE_CURRENT_COLUMN,
}

internal enum class DocumentTableInsertLimit {
    MAX_COLUMNS,
    MAX_CELLS,
}

/**
 * 可组合项与公共测试共享的纯表格交互状态。[activeCell] 是最后聚焦的单元格，
 * 因此点击操作按钮不会丢失该操作所针对的行/列。
 */
internal data class DocumentTableEditorState(
    val block: DocumentGfmTableBlock,
    val activeCell: DocumentTableCellAddress? = null,
) {
    val columnCount: Int = maxOf(
        1,
        block.headers.size,
        block.alignments.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    val normalizedHeaders: List<String> = block.headers +
        List((columnCount - block.headers.size).coerceAtLeast(0)) { "" }
    val normalizedRows: List<List<String>> = block.rows.map { row ->
        row + List((columnCount - row.size).coerceAtLeast(0)) { "" }
    }
    val normalizedAlignments: List<DocumentTableAlignment> = block.alignments +
        List((columnCount - block.alignments.size).coerceAtLeast(0)) { DocumentTableAlignment.NONE }

    private val validActiveCell: DocumentTableCellAddress? = activeCell?.takeIf { cell ->
        cell.columnIndex in 0 until columnCount &&
            (cell.isHeader || cell.rowIndex?.let { it in normalizedRows.indices } == true)
    }

    val canDeleteCurrentRow: Boolean =
        validActiveCell?.rowIndex?.let { it in normalizedRows.indices } == true
    val canDeleteCurrentColumn: Boolean = columnCount > 1 && validActiveCell != null
    val rowInsertLimit: DocumentTableInsertLimit? = when {
        columnCount > DocumentMarkdownPreviewBudget.MAX_TABLE_COLUMNS ->
            DocumentTableInsertLimit.MAX_COLUMNS
        columnCount.toLong() * (normalizedRows.size.toLong() + 2L) >
            DocumentMarkdownPreviewBudget.MAX_TABLE_CELLS.toLong() ->
            DocumentTableInsertLimit.MAX_CELLS
        else -> null
    }
    val columnInsertLimit: DocumentTableInsertLimit? = when {
        columnCount >= DocumentMarkdownPreviewBudget.MAX_TABLE_COLUMNS ->
            DocumentTableInsertLimit.MAX_COLUMNS
        (columnCount.toLong() + 1L) * (normalizedRows.size.toLong() + 1L) >
            DocumentMarkdownPreviewBudget.MAX_TABLE_CELLS.toLong() ->
            DocumentTableInsertLimit.MAX_CELLS
        else -> null
    }
    val canInsertRow: Boolean = rowInsertLimit == null
    val canInsertColumn: Boolean = columnInsertLimit == null

    fun focusHeader(columnIndex: Int): DocumentTableEditorState =
        focus(DocumentTableCellAddress(rowIndex = null, columnIndex = columnIndex))

    fun focusRow(rowIndex: Int, columnIndex: Int): DocumentTableEditorState =
        focus(DocumentTableCellAddress(rowIndex = rowIndex, columnIndex = columnIndex))

    private fun focus(cell: DocumentTableCellAddress): DocumentTableEditorState =
        if (
            cell.columnIndex in 0 until columnCount &&
            (cell.isHeader || cell.rowIndex?.let { it in normalizedRows.indices } == true)
        ) {
            copy(activeCell = cell)
        } else {
            this
        }

    fun perform(action: DocumentTableEditAction): DocumentTableEditorState = when (action) {
        DocumentTableEditAction.INSERT_ROW_AFTER -> insertRowAfterActiveCell()
        DocumentTableEditAction.INSERT_COLUMN_AFTER -> insertColumnAfterActiveCell()
        DocumentTableEditAction.DELETE_CURRENT_ROW -> deleteCurrentRow()
        DocumentTableEditAction.DELETE_CURRENT_COLUMN -> deleteCurrentColumn()
    }

    private fun insertRowAfterActiveCell(): DocumentTableEditorState {
        if (!canInsertRow) return copy(activeCell = validActiveCell)
        val insertIndex = when {
            validActiveCell == null -> normalizedRows.size
            validActiveCell.isHeader -> 0
            else -> validActiveCell.rowIndex!! + 1
        }
        val rows = normalizedRows.toMutableList().apply {
            add(insertIndex, List(columnCount) { "" })
        }
        val nextActive = validActiveCell ?: DocumentTableCellAddress(insertIndex, 0)
        return copy(
            block = block.copy(rows = rows, dirty = true),
            activeCell = nextActive,
        )
    }

    private fun insertColumnAfterActiveCell(): DocumentTableEditorState {
        if (!canInsertColumn) return copy(activeCell = validActiveCell)
        val insertIndex = (validActiveCell?.columnIndex?.plus(1) ?: columnCount)
            .coerceIn(0, columnCount)
        val headers = normalizedHeaders.toMutableList().apply {
            add(insertIndex, "列 ${columnCount + 1}")
        }
        val alignments = normalizedAlignments.toMutableList().apply {
            add(insertIndex, DocumentTableAlignment.NONE)
        }
        val rows = normalizedRows.map { row -> row.toMutableList().apply { add(insertIndex, "") } }
        val nextActive = validActiveCell ?: DocumentTableCellAddress(rowIndex = null, columnIndex = insertIndex)
        return copy(
            block = block.copy(
                headers = headers,
                alignments = alignments,
                rows = rows,
                dirty = true,
            ),
            activeCell = nextActive,
        )
    }

    private fun deleteCurrentRow(): DocumentTableEditorState {
        val rowIndex = validActiveCell?.rowIndex
            ?.takeIf { it in normalizedRows.indices }
            ?: return copy(activeCell = validActiveCell)
        val rows = normalizedRows.toMutableList().apply { removeAt(rowIndex) }
        val nextActive = if (rows.isEmpty()) {
            DocumentTableCellAddress(rowIndex = null, columnIndex = validActiveCell.columnIndex)
        } else {
            DocumentTableCellAddress(
                rowIndex = rowIndex.coerceAtMost(rows.lastIndex),
                columnIndex = validActiveCell.columnIndex,
            )
        }
        return copy(
            block = block.copy(rows = rows, dirty = true),
            activeCell = nextActive,
        )
    }

    private fun deleteCurrentColumn(): DocumentTableEditorState {
        val columnIndex = validActiveCell?.columnIndex
            ?.takeIf { columnCount > 1 }
            ?: return copy(activeCell = validActiveCell)
        val headers = normalizedHeaders.toMutableList().apply { removeAt(columnIndex) }
        val alignments = normalizedAlignments.toMutableList().apply { removeAt(columnIndex) }
        val rows = normalizedRows.map { row -> row.toMutableList().apply { removeAt(columnIndex) } }
        val nextColumn = columnIndex.coerceAtMost(columnCount - 2)
        return copy(
            block = block.copy(
                headers = headers,
                alignments = alignments,
                rows = rows,
                dirty = true,
            ),
            activeCell = validActiveCell.copy(columnIndex = nextColumn),
        )
    }
}

@Composable
private fun DocumentTableEditorRow(
    cells: List<String>,
    alignments: List<DocumentTableAlignment>,
    header: Boolean,
    testTagPrefix: String,
    activeColumn: Int?,
    onActivate: (Int) -> Unit,
    onCellChange: (Int, String) -> Unit,
    onAlignmentChange: ((Int, DocumentTableAlignment) -> Unit)? = null,
) {
    Row {
        cells.forEachIndexed { index, value ->
            val isActive = activeColumn == index
            Surface(
                border = BorderStroke(
                    if (isActive) 1.5.dp else 0.5.dp,
                    if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
                color = if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(180.dp).heightIn(min = 48.dp),
            ) {
                val alignment = alignments.getOrNull(index) ?: DocumentTableAlignment.NONE
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = decodeDocumentTableCellForVisual(value),
                        onValueChange = { onCellChange(index, it.replace('\n', ' ')) },
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                            .onFocusChanged { if (it.isFocused) onActivate(index) }
                            .testTag("$testTagPrefix.$index"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = LocalContentColor.current,
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = alignment.textAlign(),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                    )
                    if (header && onAlignmentChange != null) {
                        IconButton(
                            onClick = { onAlignmentChange(index, alignment.next()) },
                            modifier = Modifier.size(32.dp).testTag("$testTagPrefix.align.$index"),
                        ) {
                            Icon(
                                imageVector = when (alignment) {
                                    DocumentTableAlignment.CENTER -> Icons.Filled.FormatAlignCenter
                                    DocumentTableAlignment.RIGHT -> Icons.AutoMirrored.Filled.FormatAlignRight
                                    else -> Icons.AutoMirrored.Filled.FormatAlignLeft
                                },
                                contentDescription = "第 ${index + 1} 列对齐方式",
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DocumentTableAlignment.textAlign(): TextAlign = when (this) {
    DocumentTableAlignment.CENTER -> TextAlign.Center
    DocumentTableAlignment.RIGHT -> TextAlign.End
    else -> TextAlign.Start
}

private fun DocumentTableAlignment.next(): DocumentTableAlignment = when (this) {
    DocumentTableAlignment.NONE -> DocumentTableAlignment.CENTER
    DocumentTableAlignment.CENTER -> DocumentTableAlignment.RIGHT
    DocumentTableAlignment.RIGHT -> DocumentTableAlignment.LEFT
    DocumentTableAlignment.LEFT -> DocumentTableAlignment.NONE
}

/** 表格的 `\|` 是存储语法；视觉网格必须显示字面竖线。 */
internal fun decodeDocumentTableCellForVisual(markdown: String): String = buildString(markdown.length) {
    var index = 0
    while (index < markdown.length) {
        if (markdown[index] == '\\' && markdown.getOrNull(index + 1) == '|') {
            append('|')
            index += 2
        } else {
            append(markdown[index])
            index++
        }
    }
}
