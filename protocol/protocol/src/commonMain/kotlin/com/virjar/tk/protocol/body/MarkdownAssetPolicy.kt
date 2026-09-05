package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.EmbeddedAsset

/** 由标准 Markdown 语法选择的呈现方式。 */
enum class EmbeddedAssetPresentation { IMAGE, FILE }

/** 在字面 Markdown 代码之外发现的一个语义化 TeamTalk 资源引用。 */
data class MarkdownAssetReference(
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val label: String,
    val destination: String,
    val assetId: String?,
    val presentation: EmbeddedAssetPresentation,
)

/**
 * 嵌入在 Markdown 中的上下文范围资产的共享准入策略。
 *
 * Markdown 始终是放置位置的权威。单个资产可以在 Markdown 中出现多处放置，
 * 而伴随清单为每个唯一被引用的 asset id 保存恰好一个描述符，按首次放置顺序排列。
 * 字面的行内/围栏代码会被忽略，因此展示示例 URI 永远不会保留或授权任何对象。
 */
object MarkdownAssetPolicy {
    const val MAX_ASSET_REFERENCES = 1_024
    private const val INTERNAL_SCHEME_PREFIX = "teamtalk-asset:"

    fun references(markdown: String): List<MarkdownAssetReference> {
        val result = scanReferences(markdown)
        require(result.size <= MAX_ASSET_REFERENCES) {
            "单份内容不能超过 $MAX_ASSET_REFERENCES 个内嵌资产引用"
        }
        return result
    }

    /**
     * 不带提交数量闸门扫描同样的语义引用，让编辑器可以移除多余的非法规放置。
     * 这是恢复型 API，不得用于准入校验。
     */
    fun recoveryReferences(markdown: String): List<MarkdownAssetReference> = scanReferences(markdown)

    private fun scanReferences(markdown: String): List<MarkdownAssetReference> {
        if (markdown.isEmpty()) return emptyList()
        val result = ArrayList<MarkdownAssetReference>()
        var cursor = 0
        fencedCodeLiterals(markdown).forEach { literal ->
            scanInlineReferences(markdown, cursor, literal.startOffset, result)
            cursor = literal.endOffsetExclusive
        }
        scanInlineReferences(markdown, cursor, markdown.length, result)
        return result
    }

    /** 返回按 Markdown 中首次语义出现顺序排列的 canonical 清单。 */
    fun canonicalize(markdown: String, declaredAssets: List<EmbeddedAsset>): List<EmbeddedAsset> {
        require(declaredAssets.size <= EmbeddedAsset.MAX_ASSETS_PER_CONTENT) {
            "单份内容不能超过 ${EmbeddedAsset.MAX_ASSETS_PER_CONTENT} 个内嵌资产"
        }
        val canonicalById = LinkedHashMap<String, EmbeddedAsset>(declaredAssets.size)
        declaredAssets.forEach { declared ->
            val canonical = canonicalizeDescriptor(declared)
            require(canonicalById.put(canonical.assetId, canonical) == null) {
                "内嵌资产清单包含重复 assetId: ${canonical.assetId}"
            }
        }

        val references = references(markdown)
        references.forEach { reference ->
            require(reference.assetId != null) {
                "Markdown 包含非法 TeamTalk 资产地址: ${reference.destination}"
            }
        }
        val referencedIds = references.mapTo(linkedSetOf()) { checkNotNull(it.assetId) }
        require(referencedIds == canonicalById.keys) {
            "Markdown 资产引用必须与资产清单精确匹配"
        }
        references.filter { it.presentation == EmbeddedAssetPresentation.IMAGE }.forEach { reference ->
            val asset = canonicalById.getValue(checkNotNull(reference.assetId))
            require(asset.attachment.contentType.startsWith("image/", ignoreCase = true)) {
                "Markdown 图片引用必须指向图片资源: ${asset.assetId}"
            }
        }
        return referencedIds.map(canonicalById::getValue)
    }

    /**
     * 没有伴随资产清单的正文不得获得内部资源语义。代码示例仍然被允许，
     * 因为 [references] 有意排除了字面 Markdown 代码。
     */
    fun requireNoInternalReferences(markdown: String, fieldName: String): String {
        require(references(markdown).isEmpty()) {
            "$fieldName 暂不支持内嵌资产"
        }
        return markdown
    }

    /** 编码侧守卫：canonical 线格式值不得依赖解码器来归一化。 */
    fun requireCanonical(markdown: String, assets: List<EmbeddedAsset>): List<EmbeddedAsset> {
        val canonical = canonicalize(markdown, assets)
        require(canonical == assets) { "内嵌资产清单不是 canonical 形式" }
        return canonical
    }

    /**
     * 在剥离普通 Markdown 语法之前，先把内部资源语法替换为可检索的展示文本。
     * 因此存储路径与 opaque id 永远不会进入预览或搜索索引。
     */
    fun replaceReferencesForPlainText(
        markdown: String,
        canonicalAssets: List<EmbeddedAsset>,
    ): String {
        val references = references(markdown)
        if (references.isEmpty()) return markdown
        val byId = canonicalAssets.associateBy(EmbeddedAsset::assetId)
        val output = StringBuilder(markdown.length)
        var cursor = 0
        references.forEach { reference ->
            output.append(markdown, cursor, reference.startOffset)
            val asset = reference.assetId?.let(byId::get)
            val decodedLabel = decodeCommonMarkPunctuationEscapes(reference.label).trim()
            output.append(
                when (reference.presentation) {
                    EmbeddedAssetPresentation.IMAGE -> decodedLabel.ifBlank { "[图片]" }
                    EmbeddedAssetPresentation.FILE -> decodedLabel.ifBlank {
                        asset?.attachment?.name ?: "[文件]"
                    }
                },
            )
            cursor = reference.endOffsetExclusive
        }
        output.append(markdown, cursor, markdown.length)
        return output.toString()
    }

    internal fun canonicalizeDescriptor(asset: EmbeddedAsset): EmbeddedAsset {
        val assetId = EmbeddedAsset.requireCanonicalAssetId(asset.assetId)
        require(asset.width in 0..MessageBodyPolicy.MAX_MEDIA_DIMENSION) { "内嵌图片宽度非法" }
        require(asset.height in 0..MessageBodyPolicy.MAX_MEDIA_DIMENSION) { "内嵌图片高度非法" }
        val attachment = AttachmentPolicy.canonicalizeDescriptor(asset.attachment)
        val thumbnail = asset.thumbnail?.let(AttachmentPolicy::canonicalizeDescriptor)
        require(thumbnail == null || thumbnail.contentType.startsWith("image/", ignoreCase = true)) {
            "内嵌资产缩略图必须是图片"
        }
        return asset.copy(
            assetId = assetId,
            attachment = attachment,
            thumbnail = thumbnail,
        )
    }

    private fun scanInlineReferences(
        markdown: String,
        start: Int,
        endExclusive: Int,
        result: MutableList<MarkdownAssetReference>,
    ) {
        var index = start
        while (index < endExclusive) {
            val current = markdown[index]
            if (current == '\\') {
                index = minOf(index + 2, endExclusive)
                continue
            }
            if (current == '`') {
                val run = runLength(markdown, index, endExclusive, '`')
                val closing = findClosingCodeRun(markdown, index + run, endExclusive, run)
                if (closing >= 0) {
                    index = closing + run
                } else {
                    // 未匹配的分隔符是普通文本，不得遮蔽后面的链接。
                    index += run
                }
                continue
            }

            val image = current == '!' && markdown.getOrNull(index + 1) == '['
            val labelOpen = when {
                image -> index + 1
                current == '[' -> index
                else -> {
                    index++
                    continue
                }
            }
            val labelClose = findClosingLabel(markdown, labelOpen, endExclusive)
            if (labelClose < 0 || markdown.getOrNull(labelClose + 1) != '(') {
                index++
                continue
            }
            val destination = parseDestination(markdown, labelClose + 2, endExclusive)
            if (destination == null) {
                index++
                continue
            }
            val (value, closeParenthesis) = destination
            val decodedDestination = decodeCommonMarkPunctuationEscapes(value)
            // URI scheme 不区分大小写。非 canonical 拼写会被识别为内部地址然后被拒绝
            // （assetId == null），而不是被当作外部 URL 放过。
            if (decodedDestination.startsWith(INTERNAL_SCHEME_PREFIX, ignoreCase = true)) {
                result += MarkdownAssetReference(
                    startOffset = index,
                    endOffsetExclusive = closeParenthesis + 1,
                    label = markdown.substring(labelOpen + 1, labelClose),
                    destination = decodedDestination,
                    assetId = EmbeddedAsset.assetIdFromUri(decodedDestination),
                    presentation = if (image) EmbeddedAssetPresentation.IMAGE else EmbeddedAssetPresentation.FILE,
                )
            }
            index = closeParenthesis + 1
        }
    }

    private fun findClosingLabel(markdown: String, opening: Int, endExclusive: Int): Int {
        var depth = 1
        var cursor = opening + 1
        while (cursor < endExclusive) {
            when (markdown[cursor]) {
                '\\' -> cursor = minOf(cursor + 2, endExclusive)
                '[' -> {
                    depth++
                    cursor++
                }
                ']' -> {
                    depth--
                    if (depth == 0) return cursor
                    cursor++
                }
                else -> cursor++
            }
        }
        return -1
    }

    /** 解析第一个 destination 标记，并返回包裹它的右括号。 */
    private fun parseDestination(markdown: String, start: Int, endExclusive: Int): Pair<String, Int>? {
        var cursor = start
        while (cursor < endExclusive && markdown[cursor].isWhitespace()) cursor++
        if (cursor >= endExclusive) return null
        val angleWrapped = markdown[cursor] == '<'
        if (angleWrapped) cursor++
        val destinationStart = cursor
        var destinationEnd = -1
        var parenthesisDepth = 0
        while (cursor < endExclusive) {
            val current = markdown[cursor]
            if (current == '\\') {
                cursor = minOf(cursor + 2, endExclusive)
                continue
            }
            if (angleWrapped) {
                if (current == '>') {
                    destinationEnd = cursor
                    cursor++
                    break
                }
                if (current == '\n') return null
                cursor++
                continue
            }
            when {
                current == '(' -> {
                    parenthesisDepth++
                    cursor++
                }
                current == ')' && parenthesisDepth > 0 -> {
                    parenthesisDepth--
                    cursor++
                }
                current == ')' || current.isWhitespace() -> {
                    destinationEnd = cursor
                    break
                }
                else -> cursor++
            }
        }
        if (destinationEnd < 0) destinationEnd = cursor
        if (destinationEnd <= destinationStart) return null
        while (cursor < endExclusive && markdown[cursor].isWhitespace()) cursor++
        // 内部资产链接有意不支持可选的 Markdown title：展示文本属于 label/caption，
        // 接受另一种语法只会增加歧义。
        if (cursor >= endExclusive || markdown[cursor] != ')') return null
        return markdown.substring(destinationStart, destinationEnd) to cursor
    }

    /** 所有围栏代码块，包括其开始/结束行。 */
    private fun fencedCodeLiterals(markdown: String): List<MarkdownCodeLiteral> {
        val result = ArrayList<MarkdownCodeLiteral>()
        var lineStart = 0
        var activeFence: ActiveFence? = null
        while (lineStart <= markdown.length) {
            val lineEnd = markdown.indexOf('\n', lineStart).let {
                if (it < 0) markdown.length else it
            }
            val nextLineStart = if (lineEnd < markdown.length) lineEnd + 1 else lineEnd
            val marker = fenceMarker(markdown, lineStart, lineEnd)
            val active = activeFence
            if (active == null) {
                if (marker?.canOpen == true) {
                    activeFence = ActiveFence(
                        character = marker.character,
                        length = marker.length,
                        startOffset = lineStart,
                        contentStart = nextLineStart,
                    )
                }
            } else if (
                marker != null && marker.canClose && marker.character == active.character &&
                marker.length >= active.length
            ) {
                result += MarkdownCodeLiteral(
                    startOffset = active.startOffset,
                    endOffsetExclusive = nextLineStart,
                    contentStart = active.contentStart,
                    contentEndExclusive = lineStart,
                )
                activeFence = null
            }
            if (lineEnd == markdown.length) break
            lineStart = nextLineStart
        }
        activeFence?.let { active ->
            result += MarkdownCodeLiteral(
                startOffset = active.startOffset,
                endOffsetExclusive = markdown.length,
                contentStart = active.contentStart,
                contentEndExclusive = markdown.length,
            )
        }
        return result
    }

    /** 按源码顺序的字面代码区间，供纯文本推导与准入校验共用。 */
    internal fun codeLiterals(markdown: String): List<MarkdownCodeLiteral> {
        val fenced = fencedCodeLiterals(markdown)
        val result = ArrayList<MarkdownCodeLiteral>()
        var cursor = 0
        fenced.forEach { literal ->
            inlineCodeLiterals(markdown, cursor, literal.startOffset, result)
            result += literal
            cursor = literal.endOffsetExclusive
        }
        inlineCodeLiterals(markdown, cursor, markdown.length, result)
        return result
    }

    private fun inlineCodeLiterals(
        markdown: String,
        start: Int,
        endExclusive: Int,
        result: MutableList<MarkdownCodeLiteral>,
    ) {
        var cursor = start
        while (cursor < endExclusive) {
            if (markdown[cursor] == '\\') {
                cursor = minOf(cursor + 2, endExclusive)
                continue
            }
            if (markdown[cursor] != '`') {
                cursor++
                continue
            }
            val run = runLength(markdown, cursor, endExclusive, '`')
            val closing = findClosingCodeRun(markdown, cursor + run, endExclusive, run)
            if (closing < 0) {
                cursor += run
                continue
            }
            result += MarkdownCodeLiteral(
                startOffset = cursor,
                endOffsetExclusive = closing + run,
                contentStart = cursor + run,
                contentEndExclusive = closing,
            )
            cursor = closing + run
        }
    }

    private fun fenceMarker(markdown: String, start: Int, endExclusive: Int): FenceMarker? {
        var cursor = start
        var spaces = 0
        while (cursor < endExclusive && markdown[cursor] == ' ' && spaces < 4) {
            spaces++
            cursor++
        }
        if (spaces > 3 || cursor >= endExclusive) return null
        val character = markdown[cursor]
        if (character != '`' && character != '~') return null
        val length = runLength(markdown, cursor, endExclusive, character)
        if (length < 3) return null
        val suffixStart = cursor + length
        val suffixHasBacktick = character == '`' &&
            (suffixStart until endExclusive).any { markdown[it] == '`' }
        val suffixIsBlank = (suffixStart until endExclusive).all {
            markdown[it] == ' ' || markdown[it] == '\t' || markdown[it] == '\r'
        }
        return FenceMarker(
            character = character,
            length = length,
            canOpen = !suffixHasBacktick,
            canClose = suffixIsBlank,
        )
    }

    private fun runLength(markdown: String, start: Int, endExclusive: Int, character: Char): Int {
        var cursor = start
        while (cursor < endExclusive && markdown[cursor] == character) cursor++
        return cursor - start
    }

    private fun findClosingCodeRun(
        markdown: String,
        start: Int,
        endExclusive: Int,
        expectedLength: Int,
    ): Int {
        var cursor = start
        while (cursor < endExclusive) {
            if (markdown[cursor] != '`') {
                cursor++
                continue
            }
            val run = runLength(markdown, cursor, endExclusive, '`')
            if (run == expectedLength) return cursor
            cursor += run
        }
        return -1
    }

    internal data class MarkdownCodeLiteral(
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val contentStart: Int,
        val contentEndExclusive: Int,
    )

    private data class ActiveFence(
        val character: Char,
        val length: Int,
        val startOffset: Int,
        val contentStart: Int,
    )

    private data class FenceMarker(
        val character: Char,
        val length: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
    )
}
