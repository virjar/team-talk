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

        fun inspect(markdown: String): RichEditorMarkdownCapability {
            if (markdown.isEmpty()) return RichEditorMarkdownCapability(emptySet())
            if (DocumentMarkdownEditorBudget.exceeds(markdown)) {
                return RichEditorMarkdownCapability(
                    setOf(RichEditorUnsupportedMarkdownFeature.EXCESSIVE_STRUCTURE)
                )
            }

            val unsupported = linkedSetOf<RichEditorUnsupportedMarkdownFeature>()
            parser.buildMarkdownTreeFromString(markdown).visitIteratively(markdown, unsupported)
            return RichEditorMarkdownCapability(unsupported)
        }

        private fun ASTNode.visitIteratively(
            markdown: String,
            unsupported: MutableSet<RichEditorUnsupportedMarkdownFeature>,
        ) {
            val pending = ArrayDeque<Pair<ASTNode, Int>>()
            pending.addLast(this to 0)
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeAt(pending.size - 1)
                if (depth > MAX_MARKDOWN_AST_DEPTH) {
                    unsupported += RichEditorUnsupportedMarkdownFeature.EXCESSIVE_NESTING
                    continue
                }
                node.inspectNode(markdown, unsupported)
                node.children.asReversed().forEach { pending.addLast(it to depth + 1) }
            }
        }

        private fun ASTNode.inspectNode(
            markdown: String,
            unsupported: MutableSet<RichEditorUnsupportedMarkdownFeature>,
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

                MarkdownElementTypes.IMAGE ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.IMAGE

                MarkdownElementTypes.HTML_BLOCK, MarkdownTokenTypes.HTML_TAG ->
                    unsupported += RichEditorUnsupportedMarkdownFeature.RAW_HTML

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
