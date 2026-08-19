package com.virjar.tk.ui.component.rich

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Lossless, document-only projection of authoritative Markdown.
 *
 * The rich text editor remains responsible for ordinary paragraphs and inline formatting. This
 * layer only splits a document at block boundaries so a code fence, quote, table, or unknown
 * extension never forces the entire document into source mode. Every parsed block owns its exact
 * source slice. An untouched block is therefore emitted byte-for-byte; only a block explicitly
 * marked [DocumentMarkdownBlock.dirty] is encoded from its editable fields.
 */
internal sealed interface DocumentMarkdownBlock {
    /** Stable for the lifetime of a parsed editor model; callers may provide their own for inserts. */
    val key: String

    /** Exact source owned by this block, including its inter-block separators. */
    val originalMarkdown: String

    /** Source between the preceding block and this block's semantic body. */
    val leadingMarkdown: String

    /** Final source after this block. Non-empty only on the last parsed block. */
    val trailingMarkdown: String

    /** False means [originalMarkdown] must be used without normalization. */
    val dirty: Boolean
}

internal data class DocumentRichRun(
    override val key: String,
    val markdown: String,
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock

internal data class DocumentQuoteBlock(
    override val key: String,
    /** Markdown inside the outermost `>` container. Nested Markdown remains authoritative. */
    val innerMarkdown: String,
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock

internal data class DocumentCodeFenceBlock(
    override val key: String,
    val language: String? = null,
    /** Complete fence info string. Preserved when code changes; [language] is its first token. */
    val infoString: String? = language,
    val code: String = "",
    val fenceChar: Char = '`',
    val fenceLength: Int = 3,
    val openingIndent: String = "",
    val lineEnding: String = "\n",
    /** Exact line ending after the closing fence; it is not an inter-block separator. */
    val terminalLineEnding: String = "",
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock {
    init {
        require(fenceChar == '`' || fenceChar == '~') { "A Markdown fence must use backticks or tildes" }
        require(fenceLength >= 3) { "A Markdown fence must contain at least three markers" }
    }
}

internal enum class DocumentTableAlignment { NONE, LEFT, CENTER, RIGHT }

internal data class DocumentGfmTableBlock(
    override val key: String,
    /** Cell values are inline Markdown, without the surrounding pipe or padding. */
    val headers: List<String>,
    val alignments: List<DocumentTableAlignment> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val lineEnding: String = "\n",
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock

internal data class DocumentOpaqueRawBlock(
    override val key: String,
    /** Exact semantic body. A block-local source editor may update this field. */
    val rawMarkdown: String,
    val features: Set<DocumentMarkdownUnsupportedFeature> = emptySet(),
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock

internal object DocumentMarkdownBlockCodec {
    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private val richTopLevelTypes = setOf(
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.UNORDERED_LIST,
    )

    fun parse(markdown: String): List<DocumentMarkdownBlock> {
        if (markdown.isBlank()) {
            return listOf(
                DocumentRichRun(
                    key = blockKey(0, BlockKind.RICH, 0, markdown.length),
                    markdown = markdown,
                    originalMarkdown = markdown,
                    dirty = false,
                )
            )
        }

        val root = parser.buildMarkdownTreeFromString(markdown)
        val chunks = mutableListOf<SourceChunk>()
        root.children.forEach { node ->
            val start = node.startOffset.coerceIn(0, markdown.length)
            val end = node.endOffset.coerceIn(start, markdown.length)
            if (start == end || markdown.substring(start, end).isBlank()) return@forEach

            val kind = classify(node, markdown.substring(start, end))
            val previous = chunks.lastOrNull()
            if (kind == BlockKind.RICH && previous?.kind == BlockKind.RICH) {
                chunks[chunks.lastIndex] = previous.copy(end = end)
            } else {
                chunks += SourceChunk(kind = kind, start = start, end = end, node = node)
            }
        }

        // Malformed input can occasionally produce no meaningful top-level node. It is still an
        // editable rich run, and more importantly remains byte-identical until the user changes it.
        if (chunks.isEmpty()) {
            return listOf(
                DocumentRichRun(
                    key = blockKey(0, BlockKind.RICH, 0, markdown.length),
                    markdown = markdown,
                    originalMarkdown = markdown,
                    dirty = false,
                )
            )
        }

        var previousEnd = 0
        return chunks.mapIndexed { index, chunk ->
            val leading = markdown.substring(previousEnd, chunk.start)
            val trailing = if (index == chunks.lastIndex) markdown.substring(chunk.end) else ""
            val body = markdown.substring(chunk.start, chunk.end)
            previousEnd = chunk.end
            val original = leading + body + trailing
            val key = blockKey(index, chunk.kind, chunk.start, chunk.end)

            when (chunk.kind) {
                BlockKind.RICH -> DocumentRichRun(
                    key = key,
                    markdown = body,
                    originalMarkdown = original,
                    leadingMarkdown = leading,
                    trailingMarkdown = trailing,
                    dirty = false,
                )

                BlockKind.QUOTE -> DocumentQuoteBlock(
                    key = key,
                    innerMarkdown = stripOuterQuote(body),
                    originalMarkdown = original,
                    leadingMarkdown = leading,
                    trailingMarkdown = trailing,
                    dirty = false,
                )

                BlockKind.CODE_FENCE -> parseCodeBlock(
                    key = key,
                    body = body,
                    leading = leading,
                    trailing = trailing,
                    original = original,
                    fenced = chunk.node.type == MarkdownElementTypes.CODE_FENCE,
                )

                BlockKind.TABLE -> parseTable(
                    key = key,
                    body = body,
                    leading = leading,
                    trailing = trailing,
                    original = original,
                )

                BlockKind.OPAQUE -> DocumentOpaqueRawBlock(
                    key = key,
                    rawMarkdown = body,
                    features = DocumentMarkdownCompatibility.inspect(body).unsupportedFeatures,
                    originalMarkdown = original,
                    leadingMarkdown = leading,
                    trailingMarkdown = trailing,
                    dirty = false,
                )
            }
        }
    }

    fun encode(blocks: List<DocumentMarkdownBlock>): String = buildString {
        blocks.forEach { block ->
            if (!block.dirty) {
                append(block.originalMarkdown)
            } else {
                append(block.leadingMarkdown)
                append(block.encodeDirtyBody())
                append(block.trailingMarkdown)
            }
        }
    }

    private fun classify(node: ASTNode, body: String): BlockKind = when (node.type) {
        MarkdownElementTypes.BLOCK_QUOTE -> BlockKind.QUOTE
        MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> BlockKind.CODE_FENCE
        GFMElementTypes.TABLE -> BlockKind.TABLE
        in richTopLevelTypes -> if (DocumentMarkdownCompatibility.inspect(body).requiresLocalSourceBlock) {
            BlockKind.OPAQUE
        } else {
            BlockKind.RICH
        }
        else -> BlockKind.OPAQUE
    }

    private fun parseCodeBlock(
        key: String,
        body: String,
        leading: String,
        trailing: String,
        original: String,
        fenced: Boolean,
    ): DocumentCodeFenceBlock {
        if (!fenced) {
            return DocumentCodeFenceBlock(
                key = key,
                code = removeIndentedCodePrefix(body),
                lineEnding = detectLineEnding(body),
                terminalLineEnding = terminalLineEnding(body),
                originalMarkdown = original,
                leadingMarkdown = leading,
                trailingMarkdown = trailing,
                dirty = false,
            )
        }

        val lines = physicalLines(body)
        val opening = lines.firstOrNull()
        val openingText = opening?.content.orEmpty()
        val indentLength = openingText.takeWhile { it == ' ' }.length.coerceAtMost(3)
        val indent = openingText.take(indentLength)
        val afterIndent = openingText.drop(indentLength)
        val fenceChar = afterIndent.firstOrNull().takeIf { it == '`' || it == '~' } ?: '`'
        val fenceLength = afterIndent.takeWhile { it == fenceChar }.length.coerceAtLeast(3)
        val info = afterIndent.drop(fenceLength).trim()
        val language = info.takeIf(String::isNotEmpty)?.takeWhile { !it.isWhitespace() }
        val closing = lines.drop(1).firstOrNull { line ->
            val closingIndent = line.content.takeWhile { it == ' ' }.length
            if (closingIndent > 3) return@firstOrNull false
            val content = line.content.drop(closingIndent)
            val run = content.takeWhile { it == fenceChar }.length
            run >= fenceLength && content.drop(run).isBlank()
        }
        val contentStart = opening?.end ?: 0
        val contentEnd = closing?.start ?: body.length
        val rawCode = body.substring(contentStart.coerceAtMost(contentEnd), contentEnd)
        val code = if (closing != null) rawCode.removeOneTrailingLineEnding() else rawCode

        return DocumentCodeFenceBlock(
            key = key,
            language = language,
            infoString = info.takeIf(String::isNotEmpty),
            code = code,
            fenceChar = fenceChar,
            fenceLength = fenceLength,
            openingIndent = indent,
            lineEnding = opening?.separator?.ifEmpty { detectLineEnding(body) } ?: detectLineEnding(body),
            terminalLineEnding = terminalLineEnding(body),
            originalMarkdown = original,
            leadingMarkdown = leading,
            trailingMarkdown = trailing,
            dirty = false,
        )
    }

    private fun parseTable(
        key: String,
        body: String,
        leading: String,
        trailing: String,
        original: String,
    ): DocumentGfmTableBlock {
        val lines = physicalLines(body).map { it.content }
        val headers = lines.firstOrNull()?.let(::splitTableLine).orEmpty()
        val alignments = lines.getOrNull(1)?.let(::splitTableLine).orEmpty().map(::parseAlignment)
        val rows = lines.drop(2).filter(String::isNotBlank).map(::splitTableLine)
        return DocumentGfmTableBlock(
            key = key,
            headers = headers,
            alignments = alignments,
            rows = rows,
            lineEnding = detectLineEnding(body),
            originalMarkdown = original,
            leadingMarkdown = leading,
            trailingMarkdown = trailing,
            dirty = false,
        )
    }

    private fun DocumentMarkdownBlock.encodeDirtyBody(): String = when (this) {
        is DocumentRichRun -> markdown
        is DocumentQuoteBlock -> encodeQuote(innerMarkdown)
        is DocumentCodeFenceBlock -> encodeCodeFence(this)
        is DocumentGfmTableBlock -> encodeTable(this)
        is DocumentOpaqueRawBlock -> rawMarkdown
    }

    private fun encodeQuote(innerMarkdown: String): String = mapPhysicalLines(innerMarkdown) { line ->
        if (line.isEmpty()) ">" else "> $line"
    }

    private fun encodeCodeFence(block: DocumentCodeFenceBlock): String {
        val infoString = block.resolvedInfoString()
        var marker = block.fenceChar
        if (marker == '`' && infoString.orEmpty().contains('`')) marker = '~'
        val longestRun = longestRun(block.code, marker)
        val length = maxOf(3, block.fenceLength, longestRun + 1)
        val fence = marker.toString().repeat(length)
        val eol = block.lineEnding.ifEmpty { "\n" }
        return buildString {
            append(block.openingIndent)
            append(fence)
            infoString?.let(::append)
            append(eol)
            append(block.code)
            // [code] excludes the one structural separator before the closing fence. Always add
            // that separator; if code itself ends with EOL, the extra EOL preserves its blank tail.
            if (block.code.isNotEmpty()) append(eol)
            append(block.openingIndent)
            append(fence)
            append(block.terminalLineEnding)
        }
    }

    /** Preserve fence attributes while allowing the language selector to replace the first token. */
    private fun DocumentCodeFenceBlock.resolvedInfoString(): String? {
        val original = infoString?.trim()?.takeIf(String::isNotEmpty)
        val currentLanguage = language?.trim()?.takeIf(String::isNotEmpty)
        if (original == null) return currentLanguage
        val originalLanguage = original.takeWhile { !it.isWhitespace() }
        if (currentLanguage == originalLanguage) return original
        val suffix = original.drop(originalLanguage.length)
        return when {
            currentLanguage != null -> currentLanguage + suffix
            suffix.isNotBlank() -> suffix.trimStart()
            else -> null
        }
    }

    private fun encodeTable(block: DocumentGfmTableBlock): String {
        val columnCount = maxOf(
            1,
            block.headers.size,
            block.alignments.size,
            block.rows.maxOfOrNull(List<String>::size) ?: 0,
        )
        val eol = block.lineEnding.ifEmpty { "\n" }
        val headers = block.headers.padTo(columnCount)
        val alignments = block.alignments.padTo(columnCount, DocumentTableAlignment.NONE)
        val lines = buildList {
            add(encodeTableRow(headers))
            add("| " + alignments.joinToString(" | ", transform = ::encodeAlignment) + " |")
            block.rows.forEach { add(encodeTableRow(it.padTo(columnCount))) }
        }
        return lines.joinToString(eol) + if (block.originalMarkdownBodyEndsWithLineEnding()) eol else ""
    }

    private fun encodeTableRow(cells: List<String>): String =
        "| " + cells.joinToString(" | ") { escapeTableCell(it.trim()) } + " |"

    private fun encodeAlignment(alignment: DocumentTableAlignment): String = when (alignment) {
        DocumentTableAlignment.NONE -> "---"
        DocumentTableAlignment.LEFT -> ":---"
        DocumentTableAlignment.CENTER -> ":---:"
        DocumentTableAlignment.RIGHT -> "---:"
    }

    private fun parseAlignment(raw: String): DocumentTableAlignment {
        val marker = raw.trim()
        val left = marker.startsWith(':')
        val right = marker.endsWith(':')
        return when {
            left && right -> DocumentTableAlignment.CENTER
            left -> DocumentTableAlignment.LEFT
            right -> DocumentTableAlignment.RIGHT
            else -> DocumentTableAlignment.NONE
        }
    }

    private fun splitTableLine(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val pipePositions = tablePipePositions(trimmed)
        val cells = mutableListOf<String>()
        var start = 0
        pipePositions.forEach { index ->
            cells += trimmed.substring(start, index).trim()
            start = index + 1
        }
        cells += trimmed.substring(start).trim()
        if (pipePositions.firstOrNull() == 0) cells.removeFirstOrNull()
        if (pipePositions.lastOrNull() == trimmed.lastIndex) cells.removeLastOrNull()
        return cells
    }

    private fun escapeTableCell(cell: String): String {
        val singleLine = cell.replace("\r\n", "<br>").replace('\r', '\n').replace("\n", "<br>")
        return buildString {
            val separators = tablePipePositions(singleLine).toSet()
            singleLine.forEachIndexed { index, char ->
                if (char == '|' && index in separators) append('\\')
                append(char)
            }
        }
    }

    /**
     * Match JetBrains Markdown 0.7.3 `GitHubTableMarkerBlock.splitByPipes`: the table pass runs
     * before inline parsing and treats a pipe as escaped only when its immediately preceding
     * character is a backslash. Code spans do not create a special exemption, so their pipes must
     * also be written as `\|` in GFM tables.
     */
    private fun tablePipePositions(line: String): List<Int> {
        return line.indices.filter { index ->
            line[index] == '|' && (index == 0 || line[index - 1] != '\\')
        }
    }

    private fun stripOuterQuote(markdown: String): String = mapPhysicalLines(markdown) { line ->
        val indent = line.takeWhile { it == ' ' }.length.coerceAtMost(3)
        val markerIndex = indent.takeIf { line.getOrNull(it) == '>' } ?: return@mapPhysicalLines line
        var contentStart = markerIndex + 1
        if (line.getOrNull(contentStart) == ' ' || line.getOrNull(contentStart) == '\t') contentStart++
        line.substring(contentStart)
    }

    private fun removeIndentedCodePrefix(markdown: String): String = mapPhysicalLines(markdown) { line ->
        when {
            line.startsWith("    ") -> line.drop(4)
            line.startsWith('\t') -> line.drop(1)
            else -> line
        }
    }

    private fun mapPhysicalLines(markdown: String, transform: (String) -> String): String {
        if (markdown.isEmpty()) return transform("")
        return buildString(markdown.length + 16) {
            physicalLines(markdown).forEach { line ->
                append(transform(line.content))
                append(line.separator)
            }
        }
    }

    private fun physicalLines(markdown: String): List<PhysicalLine> {
        if (markdown.isEmpty()) return listOf(PhysicalLine("", "", 0, 0))
        val result = mutableListOf<PhysicalLine>()
        var start = 0
        var index = 0
        while (index < markdown.length) {
            when (markdown[index]) {
                '\r' -> {
                    val separatorEnd = if (markdown.getOrNull(index + 1) == '\n') index + 2 else index + 1
                    result += PhysicalLine(markdown.substring(start, index), markdown.substring(index, separatorEnd), start, separatorEnd)
                    start = separatorEnd
                    index = separatorEnd
                }
                '\n' -> {
                    result += PhysicalLine(markdown.substring(start, index), "\n", start, index + 1)
                    start = index + 1
                    index++
                }
                else -> index++
            }
        }
        if (start < markdown.length) result += PhysicalLine(markdown.substring(start), "", start, markdown.length)
        return result
    }

    private fun detectLineEnding(markdown: String): String {
        val crlf = markdown.indexOf("\r\n")
        val lf = markdown.indexOf('\n')
        val cr = markdown.indexOf('\r')
        return when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> "\r\n"
            lf >= 0 -> "\n"
            cr >= 0 -> "\r"
            else -> "\n"
        }
    }

    private fun terminalLineEnding(markdown: String): String = when {
        markdown.endsWith("\r\n") -> "\r\n"
        markdown.endsWith('\n') -> "\n"
        markdown.endsWith('\r') -> "\r"
        else -> ""
    }

    private fun String.removeOneTrailingLineEnding(): String = when {
        endsWith("\r\n") -> dropLast(2)
        endsWith('\n') || endsWith('\r') -> dropLast(1)
        else -> this
    }

    private fun DocumentMarkdownBlock.originalMarkdownBodyEndsWithLineEnding(): Boolean {
        val bodyEnd = originalMarkdown.length - trailingMarkdown.length
        if (bodyEnd <= leadingMarkdown.length) return false
        return originalMarkdown[bodyEnd - 1] == '\n' || originalMarkdown[bodyEnd - 1] == '\r'
    }

    private fun longestRun(text: String, target: Char): Int {
        var longest = 0
        var current = 0
        text.forEach { char ->
            if (char == target) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }

    private fun <T> List<T>.padTo(size: Int, value: T): List<T> =
        if (this.size >= size) take(size) else this + List(size - this.size) { value }

    private fun List<String>.padTo(size: Int): List<String> = padTo(size, "")

    private fun blockKey(index: Int, kind: BlockKind, start: Int, end: Int): String =
        "document-block-$index-${kind.id}-$start-$end"

    private data class SourceChunk(
        val kind: BlockKind,
        val start: Int,
        val end: Int,
        val node: ASTNode,
    )

    private data class PhysicalLine(
        val content: String,
        val separator: String,
        val start: Int,
        /** Offset immediately after this line's separator, or the source end. */
        val end: Int,
    )

    private enum class BlockKind(val id: String) {
        RICH("rich"),
        QUOTE("quote"),
        CODE_FENCE("code"),
        TABLE("table"),
        OPAQUE("raw"),
    }
}
