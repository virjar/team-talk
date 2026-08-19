package com.virjar.tk.ui.component.rich

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * 当前富文本编辑器不能无损写回的 Markdown 结构。
 *
 * 这是文档编辑入口的保守能力门禁，而不是 Markdown 预览器的能力表：命中任一结构时应使用
 * 源码模式，避免一次普通编辑把原文中尚未建模的结构静默改写或删除。
 */
internal enum class DocumentMarkdownUnsupportedFeature {
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
}

internal data class DocumentMarkdownCompatibility(
    val unsupportedFeatures: Set<DocumentMarkdownUnsupportedFeature>,
) {
    val requiresSourceMode: Boolean
        get() = unsupportedFeatures.isNotEmpty()

    companion object {
        private val parser = MarkdownParser(GFMFlavourDescriptor())

        fun inspect(markdown: String): DocumentMarkdownCompatibility {
            if (markdown.isEmpty()) return DocumentMarkdownCompatibility(emptySet())

            val unsupported = linkedSetOf<DocumentMarkdownUnsupportedFeature>()
            parser.buildMarkdownTreeFromString(markdown).visit(markdown, unsupported)
            return DocumentMarkdownCompatibility(unsupported)
        }

        private fun ASTNode.visit(
            markdown: String,
            unsupported: MutableSet<DocumentMarkdownUnsupportedFeature>,
        ) {
            when (type) {
                MarkdownElementTypes.CODE_FENCE ->
                    unsupported += DocumentMarkdownUnsupportedFeature.FENCED_CODE_BLOCK

                MarkdownElementTypes.CODE_BLOCK ->
                    unsupported += DocumentMarkdownUnsupportedFeature.INDENTED_CODE_BLOCK

                MarkdownElementTypes.BLOCK_QUOTE ->
                    unsupported += DocumentMarkdownUnsupportedFeature.BLOCK_QUOTE

                GFMElementTypes.TABLE ->
                    unsupported += DocumentMarkdownUnsupportedFeature.TABLE

                GFMTokenTypes.CHECK_BOX ->
                    unsupported += DocumentMarkdownUnsupportedFeature.TASK_LIST

                MarkdownElementTypes.IMAGE ->
                    unsupported += DocumentMarkdownUnsupportedFeature.IMAGE

                MarkdownElementTypes.HTML_BLOCK, MarkdownTokenTypes.HTML_TAG ->
                    unsupported += DocumentMarkdownUnsupportedFeature.RAW_HTML

                MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 ->
                    unsupported += DocumentMarkdownUnsupportedFeature.SETEXT_HEADING

                MarkdownElementTypes.LINK_DEFINITION,
                MarkdownElementTypes.FULL_REFERENCE_LINK,
                MarkdownElementTypes.SHORT_REFERENCE_LINK,
                -> unsupported += DocumentMarkdownUnsupportedFeature.REFERENCE_LINK

                MarkdownTokenTypes.HORIZONTAL_RULE ->
                    unsupported += DocumentMarkdownUnsupportedFeature.HORIZONTAL_RULE

                GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH ->
                    unsupported += DocumentMarkdownUnsupportedFeature.MATH

                MarkdownTokenTypes.HARD_LINE_BREAK ->
                    unsupported += DocumentMarkdownUnsupportedFeature.HARD_LINE_BREAK

                MarkdownElementTypes.LINK_TITLE ->
                    unsupported += DocumentMarkdownUnsupportedFeature.LINK_TITLE

                MarkdownElementTypes.INLINE_LINK -> if (hasFormattedLinkLabel()) {
                    unsupported += DocumentMarkdownUnsupportedFeature.FORMATTED_LINK_LABEL
                }

                MarkdownElementTypes.CODE_SPAN -> if (
                    getTextInNode(markdown).toString().takeWhile { it == '`' }.length > 1
                ) {
                    unsupported += DocumentMarkdownUnsupportedFeature.MULTI_BACKTICK_CODE_SPAN
                }

                MarkdownElementTypes.ORDERED_LIST -> if (hasNonCanonicalNumbering(markdown)) {
                    unsupported += DocumentMarkdownUnsupportedFeature.NON_CANONICAL_ORDERED_LIST
                }
            }
            children.forEach { it.visit(markdown, unsupported) }
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
        ): Boolean = children.any { child ->
            child.type in targetTypes || child.hasDescendantOfType(*targetTypes)
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
