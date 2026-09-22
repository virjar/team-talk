package com.virjar.tk.app.ui.component.rich

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * 当前可视富文本编辑器不能无损写回的 Markdown 结构。
 *
 * 这是编辑器 codec 的通用能力表，不是任一产品的预览能力表。文档会把代码、引用和表格
 * 投影为局部结构块；聊天则让整条消息留在 Markdown 源码模式。两者共用这份“能否
 * 无损进入 WYSIWYG”判定，但分别决定产品上的降级交互。
 */
internal enum class RichEditorUnsupportedMarkdownFeature {
    FENCED_CODE_BLOCK,
    INDENTED_CODE_BLOCK,
    BLOCK_QUOTE,
    TABLE,
    TASK_LIST,
    IMAGE,
    RAW_HTML,
    SETEXT_HEADING,
    REFERENCE_LINK,
    HORIZONTAL_RULE,
    MATH,
    HARD_LINE_BREAK,
    NON_CANONICAL_ORDERED_LIST,
    LINK_TITLE,
    FORMATTED_LINK_LABEL,
    MULTI_BACKTICK_CODE_SPAN,
    EXCESSIVE_NESTING,
    EXCESSIVE_STRUCTURE,
}

internal data class RichEditorMarkdownCapability(
    val unsupportedFeatures: Set<RichEditorUnsupportedMarkdownFeature>,
) {
    val requiresSourceMode: Boolean
        get() = unsupportedFeatures.isNotEmpty()

    companion object {
        private val parser = MarkdownParser(GFMFlavourDescriptor())

        /** `<br>`、`<br/>`、`<br />`（大小写不敏感）。 */
        internal val LINE_BREAK_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

        /** 除换行标签外的真实 HTML 标签（开或闭，跨行内）。 */
        private val OTHER_HTML_TAG = Regex("</?[A-Za-z][^>]*>")

        /** 文本是否只由换行标签组成；缩进代码中的字面标签必须保留源码模式。 */
        internal fun isLineBreakOnlyHtml(text: String): Boolean {
            val prefix = lineBreakHtmlPrefix(text)
            return prefix.count > 0 && prefix.endOffset == text.length
        }

        /**
         * 块首换行标签后跟普通文本的 HTML 块：CommonMark 把「`<br>` 独占一行 + 后续未空行
         * 分隔的文字」整体归为 HTML 块。只要剩余部分不含其他 HTML 标签，编辑器与预览都
         * 按换行 + Markdown 渲染，不必打回源码块；剩余部分还有真实标签则仍留在源码。
         */
        internal fun isLineBreakHeadedBlock(text: String): Boolean {
            val prefix = lineBreakHtmlPrefix(text)
            if (prefix.count == 0) return false
            return !OTHER_HTML_TAG.containsMatchIn(text.substring(prefix.endOffset))
        }

        internal data class LineBreakHtmlPrefix(val count: Int, val endOffset: Int)

        /**
         * 只移动偏移量，不逐标签复制后缀。换行后的缩进属于下一行 Markdown：四列缩进即
         * 代码，不能被 trimStart 擦掉。同一行标签间的空白不引入代码块。
         * [onBreak] 让渲染器把每个生成的换行计入与普通 AST 节点共享的预算。
         */
        internal fun lineBreakHtmlPrefix(text: String, onBreak: () -> Unit = {}): LineBreakHtmlPrefix {
            var offset = 0
            var count = 0
            var lineStart = true
            while (offset < text.length) {
                var tagStart = offset
                var indentation = 0
                while (tagStart < text.length && (text[tagStart] == ' ' || text[tagStart] == '\t')) {
                    indentation += if (text[tagStart] == '\t') 4 - indentation % 4 else 1
                    tagStart++
                }
                if (tagStart == text.length) {
                    offset = tagStart
                    break
                }
                if (text[tagStart] == '\n' || text[tagStart] == '\r') {
                    offset = tagStart + 1
                    if (text[tagStart] == '\r' && text.getOrNull(offset) == '\n') offset++
                    lineStart = true
                    continue
                }
                if (lineStart && indentation >= 4) break
                // Spaces following a tag on the same line are inline spacing, not code indentation.
                if (!lineStart) offset = tagStart
                val tag = LINE_BREAK_TAG.matchAt(text, tagStart) ?: break
                onBreak()
                count++
                offset = tag.range.last + 1
                lineStart = false
            }
            return LineBreakHtmlPrefix(count, offset)
        }

        fun inspect(markdown: String, allowCanonicalAssetImages: Boolean = false): RichEditorMarkdownCapability {
            if (markdown.isEmpty()) return RichEditorMarkdownCapability(emptySet())
            if (DocumentMarkdownEditorBudget.exceeds(markdown)) {
                return RichEditorMarkdownCapability(
                    setOf(RichEditorUnsupportedMarkdownFeature.EXCESSIVE_STRUCTURE)
                )
            }

            val unsupported = linkedSetOf<RichEditorUnsupportedMarkdownFeature>()
            parser.buildMarkdownTreeFromString(markdown).visitIteratively(markdown, unsupported, allowCanonicalAssetImages)
            return RichEditorMarkdownCapability(unsupported)
        }

        private fun ASTNode.visitIteratively(
            markdown: String,
            unsupported: MutableSet<RichEditorUnsupportedMarkdownFeature>,
            allowCanonicalAssetImages: Boolean,
        ) {
            val pending = ArrayDeque<Pair<ASTNode, Int>>()
            pending.addLast(this to 0)
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeAt(pending.size - 1)
                if (depth > MAX_MARKDOWN_AST_DEPTH) {
                    unsupported += RichEditorUnsupportedMarkdownFeature.EXCESSIVE_NESTING
                    continue
                }
                node.inspectNode(markdown, unsupported, allowCanonicalAssetImages)
                node.children.asReversed().forEach { pending.addLast(it to depth + 1) }
            }
        }

        private fun ASTNode.inspectNode(
            markdown: String,
            unsupported: MutableSet<RichEditorUnsupportedMarkdownFeature>,
            allowCanonicalAssetImages: Boolean,
        ) {
            when (type) {
                MarkdownElementTypes.CODE_FENCE ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.FENCED_CODE_BLOCK

                MarkdownElementTypes.CODE_BLOCK ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.INDENTED_CODE_BLOCK

                MarkdownElementTypes.BLOCK_QUOTE ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.BLOCK_QUOTE

                GFMElementTypes.TABLE ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.TABLE

                GFMTokenTypes.CHECK_BOX ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.TASK_LIST

                MarkdownElementTypes.IMAGE -> if (!allowCanonicalAssetImages || !isPlainCanonicalAssetImage(markdown)) {
                    unsupported += RichEditorUnsupportedMarkdownFeature.IMAGE
                }

                MarkdownElementTypes.HTML_BLOCK, MarkdownTokenTypes.HTML_TAG -> {
                    // 换行标签放行（内测反馈 T052）：纯 <br> 序列，或块首 <br> 混普通文本行
                    // （渲染为换行 + Markdown）；其余 HTML 仍视为未建模结构留在源码。
                    val text = getTextInNode(markdown).toString()
                    if (!isLineBreakOnlyHtml(text) && !isLineBreakHeadedBlock(text)) {
                        unsupported += RichEditorUnsupportedMarkdownFeature.RAW_HTML
                    }
                }

                MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.SETEXT_HEADING

                MarkdownElementTypes.LINK_DEFINITION,
                MarkdownElementTypes.FULL_REFERENCE_LINK,
                MarkdownElementTypes.SHORT_REFERENCE_LINK,
                -> unsupported += RichEditorUnsupportedMarkdownFeature.REFERENCE_LINK

                MarkdownTokenTypes.HORIZONTAL_RULE ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.HORIZONTAL_RULE

                GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.MATH

                MarkdownTokenTypes.HARD_LINE_BREAK ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.HARD_LINE_BREAK

                MarkdownElementTypes.LINK_TITLE ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.LINK_TITLE

                MarkdownElementTypes.INLINE_LINK -> if (hasFormattedLinkLabel()) {
                    unsupported += RichEditorUnsupportedMarkdownFeature.FORMATTED_LINK_LABEL
                }

                MarkdownElementTypes.CODE_SPAN -> if (
                    getTextInNode(markdown).toString().takeWhile { it == '`' }.length > 1
                ) {
                    unsupported += RichEditorUnsupportedMarkdownFeature.MULTI_BACKTICK_CODE_SPAN
                }

                MarkdownElementTypes.ORDERED_LIST -> if (hasNonCanonicalNumbering(markdown)) {
                    unsupported += RichEditorUnsupportedMarkdownFeature.NON_CANONICAL_ORDERED_LIST
                }
            }
        }

        /** 只放行简单内部图片和编码器写回的文件名下划线转义；外链、标题、复杂 alt 仍留在源码。 */
        private fun ASTNode.isPlainCanonicalAssetImage(markdown: String): Boolean {
            val destination = embeddedAssetLinkDestination(markdown) ?: return false
            if (embeddedAssetIdOrNull(destination) == null) return false
            val labelNode = findEmbeddedAssetDescendant(MarkdownElementTypes.LINK_TEXT) ?: return false
            if (labelNode.hasDescendantOfType(MarkdownElementTypes.EMPH, MarkdownElementTypes.STRONG)) return false
            val label = labelNode.getTextInNode(markdown).toString().removeSurrounding("[", "]")
            val plainLabel = label.replace("\\_", "_")
            if (plainLabel.isBlank() || plainLabel.any { !it.isLetterOrDigit() && it !in " ._-" }) return false
            return getTextInNode(markdown).toString() == "![$label]($destination)"
        }

        /** 当前编辑器只保存 link 的纯 label，内嵌样式会在写回时被压平。 */
        private fun ASTNode.hasFormattedLinkLabel(): Boolean {
            val label = children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: return false
            return label.hasDescendantOfType(
                MarkdownElementTypes.EMPH,
                MarkdownElementTypes.STRONG,
                MarkdownElementTypes.CODE_SPAN,
                GFMElementTypes.STRIKETHROUGH,
            )
        }

        private fun ASTNode.hasDescendantOfType(
            vararg targetTypes: org.intellij.markdown.IElementType,
        ): Boolean {
            val pending = ArrayDeque<ASTNode>()
            children.asReversed().forEach(pending::addLast)
            while (pending.isNotEmpty()) {
                val node = pending.removeAt(pending.size - 1)
                if (node.type in targetTypes) return true
                node.children.asReversed().forEach(pending::addLast)
            }
            return false
        }

        /**
         * 富文本编辑器目前会从 1 重新编号。标准的 1,2,3 列表可以安全进入富文本模式；
         * 非 1 起始或显式跳号需要源码模式才能保留作者写下的序号。
         */
        private fun ASTNode.hasNonCanonicalNumbering(markdown: String): Boolean {
            val numbers = children
                .filter { it.type == MarkdownElementTypes.LIST_ITEM }
                .mapNotNull { item ->
                    val firstLine = item.getTextInNode(markdown).toString()
                        .lineSequence().firstOrNull()?.trimStart().orEmpty()
                    val digits = firstLine.takeWhile(Char::isDigit)
                    digits.takeIf {
                        it.isNotEmpty() && firstLine.getOrNull(it.length) in setOf('.', ')')
                    }?.toIntOrNull()
                }
            if (numbers.isEmpty()) return false
            return numbers.first() != 1 || numbers.zipWithNext().any { (previous, next) -> next != previous + 1 }
        }
    }
}
