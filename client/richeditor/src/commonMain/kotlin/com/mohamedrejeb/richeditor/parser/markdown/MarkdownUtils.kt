package com.mohamedrejeb.richeditor.parser.markdown

import androidx.compose.ui.util.fastForEach
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

// [TT] Markdown emitted from WYSIWYG text must parse back to the same literal text. Keep the
// encoder set intentionally limited to inline constructs that otherwise change semantics; the
// decoder accepts every CommonMark backslash-escapable ASCII punctuation character.
private const val MarkdownInlineSemanticCharacters = "\\`*_[]~<>&\$"
private const val MarkdownLinkDestinationCharacters = "\\()<>"
private const val MarkdownEscapableAsciiPunctuation =
    "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

internal fun escapeMarkdownLiteralText(text: String): String =
    text.escapeMarkdownCharacters(MarkdownInlineSemanticCharacters)

internal fun escapeMarkdownLinkDestination(destination: String): String =
    destination.escapeMarkdownCharacters(MarkdownLinkDestinationCharacters)

internal fun String.decodeMarkdownPunctuationEscapes(): String = buildString(length) {
    var index = 0
    while (index < this@decodeMarkdownPunctuationEscapes.length) {
        val char = this@decodeMarkdownPunctuationEscapes[index]
        val escaped = this@decodeMarkdownPunctuationEscapes.getOrNull(index + 1)
        if (char == '\\' && escaped != null && escaped in MarkdownEscapableAsciiPunctuation) {
            append(escaped)
            index += 2
        } else {
            append(char)
            index++
        }
    }
}

private fun String.escapeMarkdownCharacters(characters: String): String = buildString(length) {
    this@escapeMarkdownCharacters.forEach { char ->
        if (char in characters) append('\\')
        append(char)
    }
}

internal fun encodeMarkdownToRichText(
    markdown: String,
    onOpenNode: (node: ASTNode) -> Unit,
    onCloseNode: (node: ASTNode) -> Unit,
    onText: (text: String) -> Unit,
    onHtmlTag: (tag: String) -> Unit,
    onHtmlBlock: (html: String) -> Unit,
) {

    val parser = MarkdownParser(GFMFlavourDescriptor())
    val tree = parser.buildMarkdownTreeFromString(markdown)
    tree.children.fastForEach { node ->
        encodeMarkdownNodeToRichText(
            node = node,
            markdown = markdown,
            onOpenNode = onOpenNode,
            onCloseNode = onCloseNode,
            onText = onText,
            onHtmlTag = onHtmlTag,
            onHtmlBlock = onHtmlBlock,
        )
    }
}

internal fun correctMarkdownText(text: String): String {
    var newText = StringBuilder()

    var pendingSpaces = 0

    var pendingTag = ""
    val lastOpenedTags = mutableListOf<String>()

    fun isCloseTag(tag: String = pendingTag) =
        tag == lastOpenedTags.lastOrNull()

    fun addPendingSpaces() {
        if (pendingSpaces > 0)
            newText.append(" ".repeat(pendingSpaces))

        pendingSpaces = 0
    }

    fun onTag(tag: String = pendingTag) {
        if (tag.isEmpty())
            return

        if (isCloseTag(tag)) {
            // On close tag

            lastOpenedTags.removeLastOrNull()
        } else {
            // On open tag

            addPendingSpaces()

            lastOpenedTags.add(tag)
        }

        newText.append(tag)

        if (tag == pendingTag)
            pendingTag = ""
    }

    fun onPendingTag() {
        while (pendingTag.isNotEmpty()) {
            val lastOpenedTag = lastOpenedTags.lastOrNull()

            if (
                lastOpenedTag == null ||
                pendingTag.first() != lastOpenedTag.first() ||
                pendingTag.length < lastOpenedTag.length
            ) {
                // Handle open tag

                val tag =
                    if (pendingTag.length >= 3)
                        pendingTag.substring(0, 3)
                    else
                        pendingTag

                val newPendingTag =
                    if (pendingTag.length >= 3)
                        pendingTag.substring(3)
                    else
                        ""

                onTag(tag)

                pendingTag = newPendingTag
            } else {
                // Handle close tag

                val tag = lastOpenedTag

                val newPendingTag =
                    pendingTag.substring(tag.length)

                onTag(tag)

                pendingTag = newPendingTag
            }
        }
    }

    fun onTextChar(char: Char) {
        onTag()

        if (pendingTag.isEmpty() || isCloseTag())
            addPendingSpaces()

        newText.append(char)
    }

    var isLineStart = false
    var isTwoSpaceIndent = false
    var isReachedFirstIndent = false
    var spaces = 0
    // Tracks whether any non-whitespace content has been emitted on the current
    // line. A `*` is a bullet-list marker (not an emphasis delimiter) when it
    // appears before any other content on its line and is followed by a space,
    // newline, or end-of-input. See #637.
    var hasLineContent = false

    text.forEachIndexed { i, char ->
        // Change indent from 2 spaces to 4 spaces
        if (char == '\n') {
            isLineStart = true
            hasLineContent = false
        } else if (isLineStart) {
            if (char == ' ') {
                spaces++
            } else if (!isReachedFirstIndent) {
                isLineStart = false
                if (spaces == 2) {
                    newText.append("  ")
                    isTwoSpaceIndent = true
                } else {
                    isTwoSpaceIndent = false
                }

                isReachedFirstIndent = spaces >= 2

                spaces = 0
            } else {
                isLineStart = false
                if (isTwoSpaceIndent && spaces >= 2) {
                    newText.append(" ".repeat(spaces))
                }

                spaces = 0
            }
        }

        // [TT] Backslash-escaped emphasis characters are literal WYSIWYG text, not tags.
        // Count the immediately preceding slash run so `\\*` remains an unescaped marker
        // while `\*` survives the normalizer unchanged.
        var precedingBackslashes = 0
        var slashIndex = i - 1
        while (slashIndex >= 0 && text[slashIndex] == '\\') {
            precedingBackslashes++
            slashIndex--
        }
        val isEscapedPunctuation = precedingBackslashes % 2 == 1

        // Extract edge spaces from tags
        if ((char == '*' || char == '~') && !isEscapedPunctuation) {
            val nextChar = text.getOrNull(i + 1)
            val isBulletMarker =
                char == '*' &&
                    !hasLineContent &&
                    pendingTag.isEmpty() &&
                    (nextChar == null || nextChar == ' ' || nextChar == '\n')

            if (isBulletMarker) {
                // Emit the star verbatim as a list-item marker without folding the
                // surrounding spaces into a paired emphasis delimiter.
                addPendingSpaces()
                newText.append(char)
                hasLineContent = true
            } else {
                if (!pendingTag.all { it == char })
                    onPendingTag()

                pendingTag += char

                if (pendingTag.length > 2)
                    onPendingTag()

                hasLineContent = true
            }
        } else if (char == ' ') {
            if (isCloseTag())
                onTag()

            pendingSpaces++
        } else {
            onTextChar(char)
            if (char != '\n') {
                hasLineContent = true
            }
        }
    }

    onTag()
    addPendingSpaces()

    return newText.toString()
}

private fun encodeMarkdownNodeToRichText(
    node: ASTNode,
    markdown: String,
    onOpenNode: (node: ASTNode) -> Unit,
    onCloseNode: (node: ASTNode) -> Unit,
    onText: (text: String) -> Unit,
    onHtmlTag: (tag: String) -> Unit,
    onHtmlBlock: (html: String) -> Unit,
) {
    when (node.type) {
        // [TT] JetBrains Markdown exposes the source slice for escaped punctuation; strip only
        // valid CommonMark punctuation escapes so `toMarkdown -> setMarkdown` restores literals.
        MarkdownTokenTypes.TEXT -> onText(
            node.getTextInNode(markdown).toString().decodeMarkdownPunctuationEscapes(),
        )
        MarkdownTokenTypes.WHITE_SPACE -> onText(" ")
        MarkdownTokenTypes.SINGLE_QUOTE -> onText("'")
        MarkdownTokenTypes.DOUBLE_QUOTE -> onText("\"")
        MarkdownTokenTypes.LPAREN -> onText("(")
        MarkdownTokenTypes.RPAREN -> onText(")")
        MarkdownTokenTypes.LBRACKET -> onText("[")
        MarkdownTokenTypes.RBRACKET -> onText("]")
        MarkdownTokenTypes.LT -> onText("<")
        MarkdownTokenTypes.GT -> onText(">")
        MarkdownTokenTypes.COLON -> onText(":")
        MarkdownTokenTypes.EXCLAMATION_MARK -> onText("!")
        MarkdownTokenTypes.EMPH -> onText("*")
        GFMTokenTypes.TILDE -> onText("~")
        MarkdownElementTypes.STRONG, GFMElementTypes.STRIKETHROUGH -> {
            onOpenNode(node)
            val children = node.children.toMutableList()
            children.removeFirstOrNull()
            children.removeFirstOrNull()
            children.removeLastOrNull()
            children.removeLastOrNull()
            children.fastForEach { child ->
                encodeMarkdownNodeToRichText(
                    node = child,
                    markdown = markdown,
                    onOpenNode = onOpenNode,
                    onCloseNode = onCloseNode,
                    onText = onText,
                    onHtmlTag = onHtmlTag,
                    onHtmlBlock = onHtmlBlock,
                )
            }
            onCloseNode(node)
        }

        MarkdownElementTypes.EMPH -> {
            onOpenNode(node)
            val children = node.children.toMutableList()
            children.removeFirstOrNull()
            children.removeLastOrNull()
            children.fastForEach { child ->
                encodeMarkdownNodeToRichText(
                    node = child,
                    markdown = markdown,
                    onOpenNode = onOpenNode,
                    onCloseNode = onCloseNode,
                    onText = onText,
                    onHtmlTag = onHtmlTag,
                    onHtmlBlock = onHtmlBlock,
                )
            }
            onCloseNode(node)
        }

        MarkdownElementTypes.CODE_SPAN -> {
            onOpenNode(node)
            onText(node.getTextInNode(markdown).removeSurrounding("`").toString())
            onCloseNode(node)
        }

        MarkdownElementTypes.INLINE_LINK -> {
            onOpenNode(node)
            val text = node
                .findChildOfType(MarkdownElementTypes.LINK_TEXT)
                ?.getTextInNode(markdown)
                ?.drop(1)
                ?.dropLast(1)
                ?.toString()
                ?.decodeMarkdownPunctuationEscapes()
            onText(text ?: "")
            onCloseNode(node)
        }

        MarkdownTokenTypes.HTML_TAG -> {
            onHtmlTag(node.getTextInNode(markdown).toString())
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            onHtmlBlock(node.getTextInNode(markdown).toString())
        }

        else -> {
            onOpenNode(node)
            node.children.fastForEach { child ->
                encodeMarkdownNodeToRichText(
                    node = child,
                    markdown = markdown,
                    onOpenNode = onOpenNode,
                    onCloseNode = onCloseNode,
                    onText = onText,
                    onHtmlTag = onHtmlTag,
                    onHtmlBlock = onHtmlBlock,
                )
            }
            onCloseNode(node)
        }
    }
}
