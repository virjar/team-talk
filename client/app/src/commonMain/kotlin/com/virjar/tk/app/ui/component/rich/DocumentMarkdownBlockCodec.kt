package com.virjar.tk.app.ui.component.rich

import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MarkdownAssetReference
import com.virjar.tk.protocol.model.EmbeddedAsset
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

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

    fun parse(
        markdown: String,
        assets: List<EmbeddedAsset> = emptyList(),
    ): List<DocumentMarkdownBlock> {
        if (DocumentMarkdownEditorBudget.exceeds(markdown)) {
            return listOf(
                DocumentOpaqueRawBlock(
                    key = blockKey(0, BlockKind.OPAQUE, 0, markdown.length),
                    rawMarkdown = markdown,
                    features = setOf(RichEditorUnsupportedMarkdownFeature.EXCESSIVE_STRUCTURE),
                    originalMarkdown = markdown,
                    dirty = false,
                )
            )
        }

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

        val scopedAssets = EmbeddedAssetRenderScope(assets)
        val root = parser.buildMarkdownTreeFromString(markdown)
        val chunks = mutableListOf<SourceChunk>()
        root.children.forEach { node ->
            val start = node.startOffset.coerceIn(0, markdown.length)
            val end = node.endOffset.coerceIn(start, markdown.length)
            if (start == end || markdown.substring(start, end).isBlank()) return@forEach

            val body = markdown.substring(start, end)
            val embeddedChunks = splitEmbeddedAssetParagraph(
                node = node,
                body = body,
                globalStart = start,
                assets = scopedAssets,
            )
            if (embeddedChunks != null) {
                embeddedChunks.forEach { chunk -> chunks.addOrMergeRich(chunk) }
                return@forEach
            }

            val kind = classify(node, body)
            val previous = chunks.lastOrNull()
            if (kind == BlockKind.RICH && previous?.kind == BlockKind.RICH) {
                chunks[chunks.lastIndex] = previous.copy(end = end)
            } else {
                chunks += SourceChunk(kind = kind, start = start, end = end, node = node)
            }
        }

        // 畸形输入偶尔会产生不了有意义的顶层节点。它仍是一个可编辑的富文本块，
        // 更重要的是在用户修改之前保持逐字节一致。
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
                ).takeUnless { DocumentMarkdownPreviewBudget.tableViolation(it) != null }
                    ?: DocumentOpaqueRawBlock(
                        key = key,
                        rawMarkdown = body,
                        features = setOf(
                            RichEditorUnsupportedMarkdownFeature.TABLE,
                            RichEditorUnsupportedMarkdownFeature.EXCESSIVE_STRUCTURE,
                        ),
                        originalMarkdown = original,
                        leadingMarkdown = leading,
                        trailingMarkdown = trailing,
                        dirty = false,
                    )

                BlockKind.IMAGE_ASSET -> DocumentEmbeddedImageBlock(
                    key = key,
                    asset = checkNotNull(chunk.asset),
                    label = checkNotNull(chunk.assetReference).label,
                    sourceMarkdown = body,
                    originalMarkdown = original,
                    leadingMarkdown = leading,
                    trailingMarkdown = trailing,
                    dirty = false,
                )

                BlockKind.FILE_ASSET -> DocumentEmbeddedFileBlock(
                    key = key,
                    asset = checkNotNull(chunk.asset),
                    label = checkNotNull(chunk.assetReference).label,
                    sourceMarkdown = body,
                    originalMarkdown = original,
                    leadingMarkdown = leading,
                    trailingMarkdown = trailing,
                    dirty = false,
                )

                BlockKind.OPAQUE -> DocumentOpaqueRawBlock(
                    key = key,
                    rawMarkdown = body,
                    features = RichEditorMarkdownCapability.inspect(body).unsupportedFeatures,
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
        in richTopLevelTypes -> if (RichEditorMarkdownCapability.inspect(body).requiresSourceMode) {
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
        is DocumentEmbeddedImageBlock -> sourceMarkdown
        is DocumentEmbeddedFileBlock -> sourceMarkdown
        is DocumentOpaqueRawBlock -> rawMarkdown
    }

    /**
     * 把直接段落子节点拆分为富文本/资源/富文本块。带样式的包装器不拆分：
     * 拆分会破坏它们的开/闭 Markdown 标记，因此保持纯源码模式。
     */
    private fun splitEmbeddedAssetParagraph(
        node: ASTNode,
        body: String,
        globalStart: Int,
        assets: EmbeddedAssetRenderScope,
    ): List<SourceChunk>? {
        if (node.type != MarkdownElementTypes.PARAGRAPH) return null
        val references = MarkdownAssetPolicy.references(body)
        if (references.isEmpty()) return null
        val resolved = references.map { reference ->
            val assetId = reference.assetId ?: return null
            val asset = assets.resolve(assetId, reference.presentation) ?: return null
            val start = globalStart + reference.startOffset
            val end = globalStart + reference.endOffsetExclusive
            val directNode = node.children.singleOrNull { child ->
                child.startOffset == start && child.endOffset == end &&
                    when (reference.presentation) {
                        EmbeddedAssetPresentation.IMAGE -> child.type == MarkdownElementTypes.IMAGE
                        EmbeddedAssetPresentation.FILE -> child.type == MarkdownElementTypes.INLINE_LINK
                    }
            } ?: return null
            Triple(reference, asset, directNode)
        }

        val result = mutableListOf<SourceChunk>()
        var cursor = 0
        resolved.forEach { (reference, asset, directNode) ->
            if (reference.startOffset > cursor) {
                val richBody = body.substring(cursor, reference.startOffset)
                if (richBody.isNotBlank()) {
                    if (RichEditorMarkdownCapability.inspect(richBody).requiresSourceMode) return null
                    result += SourceChunk(
                        kind = BlockKind.RICH,
                        start = globalStart + cursor,
                        end = globalStart + reference.startOffset,
                        node = node,
                    )
                }
            }
            result += SourceChunk(
                kind = when (reference.presentation) {
                    EmbeddedAssetPresentation.IMAGE -> BlockKind.IMAGE_ASSET
                    EmbeddedAssetPresentation.FILE -> BlockKind.FILE_ASSET
                },
                start = globalStart + reference.startOffset,
                end = globalStart + reference.endOffsetExclusive,
                node = directNode,
                assetReference = reference,
                asset = asset,
            )
            cursor = reference.endOffsetExclusive
        }
        if (cursor < body.length) {
            val richBody = body.substring(cursor)
            if (richBody.isNotBlank()) {
                if (RichEditorMarkdownCapability.inspect(richBody).requiresSourceMode) return null
                result += SourceChunk(
                    kind = BlockKind.RICH,
                    start = globalStart + cursor,
                    end = globalStart + body.length,
                    node = node,
                )
            }
        }
        return result.takeIf(List<SourceChunk>::isNotEmpty)
    }

    private fun MutableList<SourceChunk>.addOrMergeRich(chunk: SourceChunk) {
        val previous = lastOrNull()
        if (chunk.kind == BlockKind.RICH && previous?.kind == BlockKind.RICH) {
            this[lastIndex] = previous.copy(end = chunk.end)
        } else {
            add(chunk)
        }
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
            // [code] 不包含闭围栏前的那一个结构分隔符。始终补上该分隔符；
            // 若代码本身以 EOL 结尾，额外的 EOL 会保留它的空白尾行。
            if (block.code.isNotEmpty()) append(eol)
            append(block.openingIndent)
            append(fence)
            append(block.terminalLineEnding)
        }
    }

    /** 保留围栏属性，同时允许语言选择器替换第一个 token。 */
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
     * 匹配 JetBrains Markdown 0.7.3 的 `GitHubTableMarkerBlock.splitByPipes`：表格处理先于
     * 内联解析运行，且只有当竖线紧邻的前一个字符是反斜杠时才视为已转义。代码片段没有
     * 特殊豁免，因此它们在 GFM 表格中的竖线也必须写成 `\|`。
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
        val assetReference: MarkdownAssetReference? = null,
        val asset: EmbeddedAsset? = null,
    )

    private data class PhysicalLine(
        val content: String,
        val separator: String,
        val start: Int,
        /** 紧接本行分隔符之后的偏移，或源末尾。 */
        val end: Int,
    )

    private enum class BlockKind(val id: String) {
        RICH("rich"),
        QUOTE("quote"),
        CODE_FENCE("code"),
        TABLE("table"),
        IMAGE_ASSET("image-asset"),
        FILE_ASSET("file-asset"),
        OPAQUE("raw"),
    }
}

/**
 * 在 Markdown 解析器为每个块分配 AST 和编辑器模型之前的廉价预检。
 * 服务端执行同样的公开上限；此客户端守卫还保护旧/离线数据。
 */
internal object DocumentMarkdownEditorBudget {
    const val MAX_LINES = 20_000
    const val MAX_RENDERABLE_BLOCKS = 4_096
    const val MAX_UNESCAPED_PIPES_PER_LINE = 128
    const val MAX_UNESCAPED_PIPES_TOTAL = 4_096

    fun exceeds(markdown: String): Boolean {
        var lineCount = 0
        var renderableBlockUpperBound = 0
        var unescapedPipeCount = 0
        var fence: Pair<Char, Int>? = null

        markdown.lineSequence().forEach { line ->
            lineCount++
            if (lineCount > MAX_LINES) return true

            val trimmed = line.dropUpToThreeSpacesForBudget()
            val openFence = fence
            if (openFence != null) {
                val (marker, length) = openFence
                if (trimmed.firstOrNull() == marker) {
                    val run = trimmed.takeWhile { it == marker }.length
                    if (run >= length && trimmed.drop(run).isBlank()) fence = null
                }
                return@forEach
            }

            if (trimmed.isNotBlank()) {
                renderableBlockUpperBound++
                if (renderableBlockUpperBound > MAX_RENDERABLE_BLOCKS) return true
            }

            val marker = trimmed.firstOrNull().takeIf { it == '`' || it == '~' }
            if (marker != null) {
                val run = trimmed.takeWhile { it == marker }.length
                if (run >= 3 && (marker != '`' || '`' !in trimmed.drop(run))) {
                    fence = marker to run
                    return@forEach
                }
            }

            // JetBrains Markdown 0.7.3 在知道下一行是否为分隔行之前，会在每个未转义竖线处
            // 拆分所有可能的 GFM 表头。对那个临时列表同时做按行和整篇文档的有界限制。
            // 围栏代码豁免：其正文归围栏标记 provider 所有，永远不会到达表格 provider。
            var linePipeCount = 0
            trimmed.forEachIndexed { index, char ->
                if (char == '|' && (index == 0 || trimmed[index - 1] != '\\')) {
                    linePipeCount++
                    unescapedPipeCount++
                    if (
                        linePipeCount > MAX_UNESCAPED_PIPES_PER_LINE ||
                        unescapedPipeCount > MAX_UNESCAPED_PIPES_TOTAL
                    ) return true
                }
            }
        }
        return false
    }

    private fun String.dropUpToThreeSpacesForBudget(): String {
        var count = 0
        while (count < 3 && getOrNull(count) == ' ') count++
        return substring(count)
    }
}
