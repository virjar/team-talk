package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentTableEditorStateTest {

    @Test
    fun `聚焦正文单元格后在当前行下方插入而非追加到末尾`() {
        val initial = state().focusRow(rowIndex = 0, columnIndex = 1)

        val changed = initial.perform(DocumentTableEditAction.INSERT_ROW_AFTER)

        assertEquals(
            listOf(
                listOf("A1", "A2", "A3"),
                listOf("", "", ""),
                listOf("B1", "B2", "B3"),
                listOf("C1", "C2", "C3"),
            ),
            changed.block.rows,
        )
        assertEquals(DocumentTableCellAddress(rowIndex = 0, columnIndex = 1), changed.activeCell)
        assertTrue(changed.block.dirty)
    }

    @Test
    fun `聚焦表头后新增行会插入为第一条正文行`() {
        val changed = state()
            .focusHeader(columnIndex = 2)
            .perform(DocumentTableEditAction.INSERT_ROW_AFTER)

        assertEquals(listOf("", "", ""), changed.block.rows.first())
        assertEquals(listOf("A1", "A2", "A3"), changed.block.rows[1])
        assertEquals(DocumentTableCellAddress(rowIndex = null, columnIndex = 2), changed.activeCell)
    }

    @Test
    fun `聚焦单元格后在当前列右侧插入并同步表头对齐与所有行`() {
        val changed = state()
            .focusRow(rowIndex = 1, columnIndex = 0)
            .perform(DocumentTableEditAction.INSERT_COLUMN_AFTER)

        assertEquals(listOf("H1", "列 4", "H2", "H3"), changed.block.headers)
        assertEquals(
            listOf(
                DocumentTableAlignment.LEFT,
                DocumentTableAlignment.NONE,
                DocumentTableAlignment.CENTER,
                DocumentTableAlignment.RIGHT,
            ),
            changed.block.alignments,
        )
        assertEquals(listOf("A1", "", "A2", "A3"), changed.block.rows[0])
        assertEquals(listOf("B1", "", "B2", "B3"), changed.block.rows[1])
        assertEquals(DocumentTableCellAddress(rowIndex = 1, columnIndex = 0), changed.activeCell)
    }

    @Test
    fun `删除当前行后选中相邻行且不会误删末行`() {
        val changed = state()
            .focusRow(rowIndex = 1, columnIndex = 2)
            .perform(DocumentTableEditAction.DELETE_CURRENT_ROW)

        assertEquals(
            listOf(
                listOf("A1", "A2", "A3"),
                listOf("C1", "C2", "C3"),
            ),
            changed.block.rows,
        )
        assertEquals(DocumentTableCellAddress(rowIndex = 1, columnIndex = 2), changed.activeCell)
    }

    @Test
    fun `删除当前列会同步删除每行同一列并把焦点移到相邻列`() {
        val changed = state()
            .focusHeader(columnIndex = 1)
            .perform(DocumentTableEditAction.DELETE_CURRENT_COLUMN)

        assertEquals(listOf("H1", "H3"), changed.block.headers)
        assertEquals(
            listOf(DocumentTableAlignment.LEFT, DocumentTableAlignment.RIGHT),
            changed.block.alignments,
        )
        assertEquals(listOf("A1", "A3"), changed.block.rows[0])
        assertEquals(listOf("B1", "B3"), changed.block.rows[1])
        assertEquals(DocumentTableCellAddress(rowIndex = null, columnIndex = 1), changed.activeCell)
    }

    @Test
    fun `无正文行焦点或只剩一列时删除操作保持原表`() {
        val headerFocused = state().focusHeader(columnIndex = 0)
        val afterRowDelete = headerFocused.perform(DocumentTableEditAction.DELETE_CURRENT_ROW)
        assertFalse(headerFocused.canDeleteCurrentRow)
        assertEquals(headerFocused.block, afterRowDelete.block)

        val singleColumn = DocumentTableEditorState(
            block = DocumentGfmTableBlock(
                key = "single-column",
                headers = listOf("唯一列"),
                rows = listOf(listOf("值")),
                dirty = false,
            ),
        ).focusRow(rowIndex = 0, columnIndex = 0)
        val afterColumnDelete = singleColumn.perform(DocumentTableEditAction.DELETE_CURRENT_COLUMN)
        assertFalse(singleColumn.canDeleteCurrentColumn)
        assertEquals(singleColumn.block, afterColumnDelete.block)
    }

    @Test
    fun `未聚焦时新增仍保持末尾惯例并选中新单元格`() {
        val rowAdded = state().perform(DocumentTableEditAction.INSERT_ROW_AFTER)
        assertEquals(listOf("", "", ""), rowAdded.block.rows.last())
        assertEquals(DocumentTableCellAddress(rowIndex = 3, columnIndex = 0), rowAdded.activeCell)

        val columnAdded = state().perform(DocumentTableEditAction.INSERT_COLUMN_AFTER)
        assertEquals("列 4", columnAdded.block.headers.last())
        assertTrue(columnAdded.block.rows.all { it.last().isEmpty() })
        assertEquals(DocumentTableCellAddress(rowIndex = null, columnIndex = 3), columnAdded.activeCell)
    }

    @Test
    fun `三十一列可新增到三十二列但不能继续新增到三十三列`() {
        val atThirtyOne = tableState(columns = 31, dataRows = 1)

        assertTrue(atThirtyOne.canInsertColumn)
        assertEquals(null, atThirtyOne.columnInsertLimit)

        val atThirtyTwo = atThirtyOne.perform(DocumentTableEditAction.INSERT_COLUMN_AFTER)
        assertEquals(32, atThirtyTwo.columnCount)
        assertFalse(atThirtyTwo.canInsertColumn)
        assertEquals(DocumentTableInsertLimit.MAX_COLUMNS, atThirtyTwo.columnInsertLimit)

        val rejected = atThirtyTwo.perform(DocumentTableEditAction.INSERT_COLUMN_AFTER)
        assertEquals(atThirtyTwo.block, rejected.block)
        assertEquals(32, rejected.columnCount)
    }

    @Test
    fun `新增行可以恰好达到一千单元格但不能越过`() {
        // 10 列 ×（98 条正文行 + 1 条表头）= 990；新增一行后恰好为 1000。
        val atNineHundredNinety = tableState(columns = 10, dataRows = 98)

        assertTrue(atNineHundredNinety.canInsertRow)
        val atOneThousand = atNineHundredNinety.perform(DocumentTableEditAction.INSERT_ROW_AFTER)
        assertEquals(99, atOneThousand.block.rows.size)
        assertEquals(1_000, atOneThousand.columnCount * (atOneThousand.block.rows.size + 1))
        assertFalse(atOneThousand.canInsertRow)
        assertEquals(DocumentTableInsertLimit.MAX_CELLS, atOneThousand.rowInsertLimit)

        val rejected = atOneThousand.perform(DocumentTableEditAction.INSERT_ROW_AFTER)
        assertEquals(atOneThousand.block, rejected.block)
        assertEquals(99, rejected.block.rows.size)
    }

    @Test
    fun `新增列也不能让可视单元格越过一千`() {
        // 9 列 ×（99 条正文行 + 1 条表头）= 900；新增一列后恰好为 1000。
        val atNineHundred = tableState(columns = 9, dataRows = 99)
        assertTrue(atNineHundred.canInsertColumn)

        val atOneThousand = atNineHundred.perform(DocumentTableEditAction.INSERT_COLUMN_AFTER)
        assertEquals(10, atOneThousand.columnCount)
        assertFalse(atOneThousand.canInsertColumn)
        assertEquals(DocumentTableInsertLimit.MAX_CELLS, atOneThousand.columnInsertLimit)

        val rejected = atOneThousand.perform(DocumentTableEditAction.INSERT_COLUMN_AFTER)
        assertEquals(atOneThousand.block, rejected.block)
        assertEquals(10, rejected.columnCount)
    }

    private fun state(): DocumentTableEditorState = DocumentTableEditorState(
        block = DocumentGfmTableBlock(
            key = "table",
            headers = listOf("H1", "H2", "H3"),
            alignments = listOf(
                DocumentTableAlignment.LEFT,
                DocumentTableAlignment.CENTER,
                DocumentTableAlignment.RIGHT,
            ),
            rows = listOf(
                listOf("A1", "A2", "A3"),
                listOf("B1", "B2", "B3"),
                listOf("C1", "C2", "C3"),
            ),
            dirty = false,
        ),
    )

    private fun tableState(columns: Int, dataRows: Int): DocumentTableEditorState =
        DocumentTableEditorState(
            block = DocumentGfmTableBlock(
                key = "table-$columns-$dataRows",
                headers = List(columns) { "H${it + 1}" },
                alignments = List(columns) { DocumentTableAlignment.NONE },
                rows = List(dataRows) { row -> List(columns) { column -> "$row:$column" } },
                dirty = false,
            ),
        )
}
