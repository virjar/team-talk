package com.virjar.tk.domain.document

/**
 * 文档 Markdown 的线性结构预算检查。
 *
 * 不建立 AST，也不随引用深度递归；每一行只进行常数次扫描。正文总长度仍由
 * [DocumentService.MAX_MARKDOWN_LENGTH] 独立控制。围栏代码里的 `>` 和 `|` 是字面内容，
 * 不参与引用或表格预算。
 */
internal object DocumentMarkdownStructurePolicy {

    fun validate(
        markdown: String,
        maxQuoteDepth: Int,
        maxTableColumns: Int,
        maxTableCells: Int,
        maxLines: Int,
        maxRenderableBlocks: Int,
    ) {
        require(
            maxQuoteDepth > 0 && maxTableColumns > 0 && maxTableCells > 0 &&
                maxLines > 0 && maxRenderableBlocks > 0
        )

        var fence: Fence? = null
        var pendingHeader: TableHeader? = null
        var activeTable: TableState? = null
        var lineCount = 0
        var renderableBlockUpperBound = 0

        markdown.lineSequence().forEach { physicalLine ->
            lineCount++
            require(lineCount <= maxLines) { "文档正文不能超过 $maxLines 行" }

            val openFence = fence
            if (openFence != null) {
                // CommonMark 允许列表/引用中的 fenced code 使用未带容器前缀的空白行。
                if (physicalLine.isBlank()) return@forEach
                val content = physicalLine.afterRequiredContainers(openFence.containers)
                if (content != null) {
                    if (content.isClosingFence(openFence)) fence = null
                    return@forEach
                }
                // A fenced block inside a quote/list ends when the physical line no longer
                // satisfies the opening containers. Reprocess this same line as ordinary
                // Markdown; otherwise one unmatched fence could suppress every later budget.
                fence = null
            }

            val line = physicalLine.stripQuotePrefix()
            require(line.depth <= maxQuoteDepth) {
                "文档引用层级不能超过 $maxQuoteDepth 层"
            }

            // A Markdown top-level node needs at least one non-empty physical source line. Counting
            // every such line is deliberately conservative and, unlike building an AST, bounds
            // allocation before an untrusted document reaches any client parser. Fenced-code body
            // lines are excluded above because an entire fence is one renderable document block.
            if (line.content.isNotBlank()) {
                renderableBlockUpperBound++
                require(renderableBlockUpperBound <= maxRenderableBlocks) {
                    "文档可视内容块不能超过 $maxRenderableBlocks 个"
                }
            }

            line.content.openingFenceOrNull()?.let { marker ->
                fence = Fence(marker.first, marker.second, line.containers)
                pendingHeader = null
                activeTable = null
                return@forEach
            }

            // Parse the pipe shape at most once per physical line. The summary is constant-size and
            // can be reused when this line terminates one table and becomes the next possible header.
            val tableLine = line.content.tableLineOrNull()
            val table = activeTable
            if (table != null) {
                if (line.depth == table.quoteDepth && line.content.isNotBlank() && tableLine != null) {
                    table.addRow(tableLine.cellCount)
                    table.requireWithin(maxTableColumns, maxTableCells)
                    return@forEach
                }
                activeTable = null
            }

            val header = pendingHeader
            if (header != null) {
                if (
                    line.depth == header.quoteDepth &&
                    tableLine != null &&
                    tableLine.cellCount == header.cellCount &&
                    tableLine.allCellsAreGfmDelimiters
                ) {
                    activeTable = TableState(
                        quoteDepth = line.depth,
                        columnCount = header.cellCount,
                    ).also { it.requireWithin(maxTableColumns, maxTableCells) }
                    pendingHeader = null
                    return@forEach
                }
                pendingHeader = null
            }

            tableLine?.let { candidate ->
                if (line.content.isNotBlank()) pendingHeader = TableHeader(line.depth, candidate.cellCount)
            }
        }
    }

    private data class QuoteLine(
        val depth: Int,
        val content: String,
        val containers: List<ContainerConstraint>,
    )
    private data class Fence(
        val marker: Char,
        val length: Int,
        val containers: List<ContainerConstraint>,
    )
    private data class TableHeader(val quoteDepth: Int, val cellCount: Int)
    private data class TableLine(
        val cellCount: Int,
        val allCellsAreGfmDelimiters: Boolean,
    )

    private enum class DelimiterState {
        START,
        LEFT_COLON,
        HYPHENS,
        RIGHT_COLON,
        INVALID,
    }

    private sealed interface ContainerConstraint {
        data object Quote : ContainerConstraint
        data class ListIndent(val columns: Int) : ContainerConstraint
    }

    private data class TableState(
        val quoteDepth: Int,
        var columnCount: Int,
        var bodyRows: Int = 0,
    ) {
        fun addRow(cells: Int) {
            columnCount = maxOf(columnCount, cells)
            bodyRows++
        }

        fun requireWithin(maxColumns: Int, maxCells: Int) {
            require(columnCount <= maxColumns) { "文档表格不能超过 $maxColumns 列" }
            val renderedCells = columnCount.toLong() * (bodyRows.toLong() + 1L)
            require(renderedCells <= maxCells) { "单个文档表格不能超过 $maxCells 个单元格" }
        }
    }

    /**
     * 逐层消费 Markdown 容器前缀。引用可以位于 `- `、`1. ` 等列表容器之后，且列表与
     * 引用可以交替嵌套；只统计 `>`，列表本身不占引用预算。
     */
    private fun String.stripQuotePrefix(): QuoteLine {
        var cursor = 0
        var depth = 0
        val containers = mutableListOf<ContainerConstraint>()
        while (cursor < length) {
            val levelStart = cursor
            var spaces = 0
            while (spaces < 3 && getOrNull(cursor) == ' ') {
                cursor++
                spaces++
            }
            when (getOrNull(cursor)) {
                '>' -> {
                    cursor++
                    if (getOrNull(cursor) == ' ' || getOrNull(cursor) == '\t') cursor++
                    depth++
                    containers += ContainerConstraint.Quote
                }
                '-', '+', '*' -> {
                    if (getOrNull(cursor + 1) != ' ' && getOrNull(cursor + 1) != '\t') {
                        cursor = levelStart
                        break
                    }
                    cursor += 2
                    containers += ContainerConstraint.ListIndent(
                        columns = indentationColumns(levelStart, cursor),
                    )
                }
                in '0'..'9' -> {
                    val digitStart = cursor
                    while (cursor - digitStart < 9 && getOrNull(cursor)?.isDigit() == true) cursor++
                    if (
                        cursor == digitStart ||
                        getOrNull(cursor) !in setOf('.', ')') ||
                        (getOrNull(cursor + 1) != ' ' && getOrNull(cursor + 1) != '\t')
                    ) {
                        cursor = levelStart
                        break
                    }
                    cursor += 2
                    containers += ContainerConstraint.ListIndent(
                        columns = indentationColumns(levelStart, cursor),
                    )
                }
                else -> {
                    cursor = levelStart
                    break
                }
            }
        }
        return QuoteLine(depth, substring(cursor), containers)
    }

    /** 围栏正文只消费创建时已有的容器；额外 `>` 与缩进仍是代码字面量。 */
    private fun String.afterRequiredContainers(containers: List<ContainerConstraint>): String? {
        var cursor = 0
        containers.forEach { container ->
            when (container) {
                ContainerConstraint.Quote -> {
                    var spaces = 0
                    while (spaces < 3 && getOrNull(cursor) == ' ') {
                        cursor++
                        spaces++
                    }
                    if (getOrNull(cursor) != '>') return null
                    cursor++
                    if (getOrNull(cursor) == ' ' || getOrNull(cursor) == '\t') cursor++
                }
                is ContainerConstraint.ListIndent -> {
                    cursor = consumeIndentationColumns(cursor, container.columns) ?: return null
                }
            }
        }
        return substring(cursor)
    }

    private fun String.indentationColumns(start: Int, end: Int): Int {
        var columns = 0
        for (index in start until end) {
            columns = if (this[index] == '\t') {
                columns + (4 - columns % 4)
            } else {
                columns + 1
            }
        }
        return columns
    }

    private fun String.consumeIndentationColumns(start: Int, requiredColumns: Int): Int? {
        var cursor = start
        var columns = 0
        while (columns < requiredColumns) {
            columns = when (getOrNull(cursor)) {
                ' ' -> columns + 1
                '\t' -> columns + (4 - columns % 4)
                else -> return null
            }
            cursor++
        }
        return cursor
    }

    private fun String.openingFenceOrNull(): Pair<Char, Int>? {
        val content = dropUpToThreeSpaces()
        val marker = content.firstOrNull().takeIf { it == '`' || it == '~' } ?: return null
        val length = content.takeWhile { it == marker }.length
        if (length < 3) return null
        // CommonMark 不允许反引号围栏的 info string 再包含反引号。
        if (marker == '`' && '`' in content.drop(length)) return null
        return marker to length
    }

    private fun String.isClosingFence(fence: Fence): Boolean {
        val content = dropUpToThreeSpaces()
        if (content.firstOrNull() != fence.marker) return false
        val run = content.takeWhile { it == fence.marker }.length
        return run >= fence.length && content.drop(run).isBlank()
    }

    private fun String.dropUpToThreeSpaces(): String {
        var count = 0
        while (count < 3 && getOrNull(count) == ' ') count++
        return substring(count)
    }

    /**
     * 与客户端表格 codec 一致地扫描一行，但不物化 separator/cell 列表或 cell 子串。
     *
     * JetBrains GFM 以任意管道（包括 `\|`）判断正文行仍可能属于表格，再按未转义
     * 管道切格。因此只有转义管道的行仍按一个单元格返回。普通正文可能合法包含几十万
     * 个管道；这里只保留列数和“是否全部为 delimiter”两个标量，避免预检本身成为内存
     * 放大器。只有确认下一行是匹配的 delimiter 后，上层才应用表格列数预算。
     */
    private fun String.tableLineOrNull(): TableLine? {
        var trimmedStart = 0
        while (trimmedStart < length && this[trimmedStart].isWhitespace()) trimmedStart++
        var trimmedEnd = length
        while (trimmedEnd > trimmedStart && this[trimmedEnd - 1].isWhitespace()) trimmedEnd--
        if (trimmedStart == trimmedEnd) return null

        var containsPipe = false
        var separatorCount = 0
        var lastSeparator = -1
        var cellCount = 0
        var allDelimiters = true
        var delimiterState = DelimiterState.START
        var hasDelimiterMarker = false
        var pendingTrimmedWhitespace = false

        fun resetCell() {
            delimiterState = DelimiterState.START
            hasDelimiterMarker = false
            pendingTrimmedWhitespace = false
        }

        fun consumeCellCharacter(char: Char) {
            if (!allDelimiters || delimiterState == DelimiterState.INVALID) return
            when {
                char == ' ' || char == '\t' -> Unit
                char.isWhitespace() -> if (hasDelimiterMarker) pendingTrimmedWhitespace = true
                else -> {
                    if (pendingTrimmedWhitespace) {
                        // `trim()` only removes whitespace at cell edges. Other Unicode whitespace
                        // between marker characters therefore makes the delimiter invalid.
                        delimiterState = DelimiterState.INVALID
                        return
                    }
                    hasDelimiterMarker = true
                    delimiterState = when (delimiterState) {
                        DelimiterState.START -> if (char == ':') {
                            DelimiterState.LEFT_COLON
                        } else if (char == '-') {
                            DelimiterState.HYPHENS
                        } else {
                            DelimiterState.INVALID
                        }
                        DelimiterState.LEFT_COLON -> if (char == '-') {
                            DelimiterState.HYPHENS
                        } else {
                            DelimiterState.INVALID
                        }
                        DelimiterState.HYPHENS -> when (char) {
                            '-' -> DelimiterState.HYPHENS
                            ':' -> DelimiterState.RIGHT_COLON
                            else -> DelimiterState.INVALID
                        }
                        DelimiterState.RIGHT_COLON,
                        DelimiterState.INVALID,
                        -> DelimiterState.INVALID
                    }
                }
            }
        }

        fun countCell() {
            cellCount++
            val delimiterIsValid = hasDelimiterMarker && (
                delimiterState == DelimiterState.HYPHENS ||
                    delimiterState == DelimiterState.RIGHT_COLON
                )
            if (allDelimiters && !delimiterIsValid) {
                allDelimiters = false
            }
            resetCell()
        }

        for (index in trimmedStart until trimmedEnd) {
            val char = this[index]
            if (char != '|') {
                consumeCellCharacter(char)
                continue
            }
            containsPipe = true
            if (index > trimmedStart && this[index - 1] == '\\') {
                consumeCellCharacter(char)
                continue
            }

            // The empty slice before a leading pipe is not a GFM cell. Every later slice, including
            // an empty slice between adjacent pipes, is a real cell and must be counted.
            if (!(separatorCount == 0 && index == trimmedStart)) countCell() else resetCell()
            separatorCount++
            lastSeparator = index
        }

        if (!containsPipe) return null
        if (separatorCount == 0) {
            countCell()
            return TableLine(cellCount = 1, allCellsAreGfmDelimiters = allDelimiters)
        }

        // The empty slice after a trailing pipe is not a GFM cell.
        if (lastSeparator != trimmedEnd - 1) countCell()
        return cellCount.takeIf { it > 0 }?.let { TableLine(it, allDelimiters) }
    }
}
