package com.virjar.tk.ui.component.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.ui.theme.Tk
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 富文本消息渲染（markdown）——自研渲染层。
 *
 * 选型（doc/10-rich-messaging/README.md）：只用 JetBrains 官方 parser（`org.jetbrains:markdown`，
 * 纯 Kotlin 无传递依赖），AST → 块模型 → AnnotatedString/Compose 组件自行渲染。
 * 放弃 mikepenz 渲染器：其 JVM 字节码为 Java 21（class 65），本项目运行时 JBR 17 只认 61，
 * 运行期 UnsupportedClassVersionError（F17）；且纯 Text 方案没有 inlineContent（mention 胶囊/卡片受限）。
 *
 * 支持子集（IM 场景）：段落/粗体/斜体/删除线/行内代码/链接（含 mention://）/代码块/标题/列表/引用。
 * 普通文本无语法时视觉等同纯文本。颜色全部取自气泡 LocalContentColor（蓝/灰气泡自适应）。
 */

// ── 块/行内模型（parser AST 的稳定投影，渲染层不触 AST） ──

internal sealed class MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class CodeFence(val lang: String?, val code: String) : MdBlock()
    data class Quote(val blocks: List<MdBlock>) : MdBlock()
    data class ListItem(val spans: List<MdSpan>) : MdBlock()
}

internal sealed class MdSpan {
    data class Text(val text: String) : MdSpan()
    data class Styled(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
    ) : MdSpan()

    data class Link(val label: String, val url: String) : MdSpan()

    /** mention://uid 链接语法：`@[显示名](mention://uid)` */
    data class Mention(val uid: String, val name: String) : MdSpan()
}

internal object MdParser {
    // GFM：比 commonmark 多删除线/表格（表格暂不渲染，删除线 IM 常用）
    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    /** 语法标记 token（星号、波浪号、反引号、方圆括号等）：只参与结构不产出文本，逐出渲染（曾泄漏为可见字符） */
    private val markerTokens: Set<org.intellij.markdown.IElementType> = setOf(
        MarkdownTokenTypes.EMPH, MarkdownTokenTypes.BACKTICK, MarkdownTokenTypes.ESCAPED_BACKTICKS,
        MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.EXCLAMATION_MARK, MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.LT, MarkdownTokenTypes.GT,
        GFMTokenTypes.TILDE,
        MarkdownTokenTypes.SINGLE_QUOTE, MarkdownTokenTypes.DOUBLE_QUOTE,
        MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER,
    )

    fun parse(src: String): List<MdBlock> = parser.buildMarkdownTreeFromString(src)
        .children.flatMap { it.toBlocks(src) }

    /** 列表容器（UNORDERED_LIST/ORDERED_LIST）透传子项，其余块类型一一块映射。 */
    private fun ASTNode.toBlocks(src: String): List<MdBlock> = when (type) {
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST ->
            children.mapNotNull { child -> child.toBlock(src) }
        else -> listOfNotNull(toBlock(src))
    }

    private fun ASTNode.toBlock(src: String): MdBlock? = when (type) {
        MarkdownElementTypes.PARAGRAPH -> MdBlock.Paragraph(inline(src))
        MarkdownElementTypes.ATX_1 -> MdBlock.Heading(1, inline(src))
        MarkdownElementTypes.ATX_2 -> MdBlock.Heading(2, inline(src))
        MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> MdBlock.Heading(3, inline(src))
        MarkdownElementTypes.CODE_FENCE -> MdBlock.CodeFence(
            lang = findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(src)?.toString()?.trim(),
            code = findChildOfType(MarkdownTokenTypes.CODE_FENCE_CONTENT)?.getTextInNode(src)?.toString()?.trimEnd('\n') ?: "",
        )
        MarkdownElementTypes.CODE_BLOCK -> MdBlock.CodeFence(lang = null, code = getTextInNode(src).toString().trimEnd('\n'))
        MarkdownElementTypes.BLOCK_QUOTE -> MdBlock.Quote(children.flatMap { it.toBlocks(src) })
        // 列表项上下文：marker token（- / 1.）一律逐出（它们不是用户内容；与段落内
        // 未闭合语法的字面 marker 语义不同——后者是用户敲的字符要保序）
        MarkdownElementTypes.LIST_ITEM -> MdBlock.ListItem(spans = inline(src, literalMarkers = false))
        else -> null // 未支持块（HTML/链接定义等）丢弃，IM 消息不出现
    }

    /**
     * @param literalMarkers 段落层孤立的 marker token（未闭合语法的 `**` 等）是否字面显示。
     *   段落内 true（保序不丢字）；列表项 false（`- ` 是结构不是内容）。
     */
    private fun ASTNode.inline(src: String, literalMarkers: Boolean = true): List<MdSpan> {
        val out = mutableListOf<MdSpan>()

        /**
         * @param inStructure 当前是否处于语法结构内（EMPH/STRONG/LINK/CODE_SPAN 子树）。
         *   marker token 在结构内 = 语法符号（逐出）；在段落层孤立出现 = 未闭合语法的字面字符（保留，不丢字）。
         */
        fun walk(node: ASTNode, style: MdSpan.Styled, inStructure: Boolean) {
            when (node.type) {
                MarkdownElementTypes.EMPH -> node.children.forEach { walk(it, style.copy(italic = true), true) }
                MarkdownElementTypes.STRONG -> node.children.forEach { walk(it, style.copy(bold = true), true) }
                GFMElementTypes.STRIKETHROUGH -> node.children.forEach { walk(it, style.copy(strike = true), true) }
                MarkdownElementTypes.CODE_SPAN -> out += MdSpan.Styled(
                    node.getTextInNode(src).toString().trim('`'),
                    bold = style.bold, italic = style.italic, strike = style.strike, code = true,
                )
                MarkdownElementTypes.INLINE_LINK -> {
                    val dest = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                        ?.getTextInNode(src)?.toString()
                    // 显示文本在 LINK_TEXT 子树内（含 [ ] 包裹，取其 TEXT 叶子拼接）
                    val label = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                        ?.children?.filter { it.type == MarkdownTokenTypes.TEXT }
                        ?.joinToString("") { it.getTextInNode(src).toString() }
                        .orEmpty()
                    when {
                        dest != null && dest.startsWith("mention://") ->
                            out += MdSpan.Mention(uid = dest.removePrefix("mention://"), name = label.ifBlank { dest })
                        dest != null -> out += MdSpan.Link(label = label.ifBlank { dest }, url = dest)
                        else -> out += MdSpan.Styled(label.ifBlank { node.getTextInNode(src).toString() }, style.bold, style.italic, style.strike, style.code)
                    }
                }
                MarkdownElementTypes.AUTOLINK -> {
                    val url = node.getTextInNode(src).toString().trim('<', '>')
                    out += MdSpan.Link(label = url, url = url)
                }
                else -> {
                    // 叶子：语法标记 token 逐出，正文原样保留（保序不丢字）；
                    // 无样式叠加时产出 Text（而非空 Styled，纯文本零包装）
                    if (node.children.isEmpty()) {
                        if (node.type !in markerTokens || !inStructure) {
                            val text = node.getTextInNode(src).toString()
                            if (text.isNotEmpty()) {
                                out += if (style.bold || style.italic || style.strike || style.code) {
                                    style.copy(text = text)
                                } else {
                                    MdSpan.Text(text)
                                }
                            }
                        }
                    } else {
                        node.children.forEach { walk(it, style, inStructure) }
                    }
                }
            }
        }
        children.forEach { walk(it, MdSpan.Styled(""), inStructure = !literalMarkers) }
        return out
    }
}

// ── 渲染 ──

/**
 * @param onUrlClick 链接点击（null 时链接仅着色不可点）
 * @param onMentionClick @提及点击（打开用户资料）
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    onUrlClick: ((String) -> Unit)? = null,
    onMentionClick: ((uid: String) -> Unit)? = null,
) {
    val blocks = remember(content) { MdParser.parse(content) }
    val contentColor = LocalContentColor.current

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val topPadding = if (index == 0) 0.dp else Tk.spacing.xs
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = block.spans.toAnnotated(onUrlClick, onMentionClick),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.padding(top = topPadding).fillMaxWidth(),
                )
                is MdBlock.Heading -> Text(
                    text = block.spans.toAnnotated(onUrlClick, onMentionClick),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = contentColor,
                    modifier = Modifier.padding(top = topPadding.coerceAtLeast(Tk.spacing.sm)).fillMaxWidth(),
                )
                is MdBlock.CodeFence -> Row(Modifier.padding(top = topPadding)) {
                    CodeFenceBox(block, contentColor)
                }
                is MdBlock.Quote -> Row(Modifier.padding(top = topPadding).height(IntrinsicSize.Min)) {
                    // 引用块：与回复卡片同语言的左侧竖线
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(contentColor.copy(alpha = 0.4f)),
                    )
                    Spacer(Modifier.width(Tk.spacing.sm))
                    Column {
                        block.blocks.forEach { sub ->
                            if (sub is MdBlock.Paragraph) {
                                Text(
                                    text = sub.spans.toAnnotated(onUrlClick, onMentionClick),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentColor.copy(alpha = 0.85f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                is MdBlock.ListItem -> Row(Modifier.padding(top = topPadding)) {
                    Text("• ", style = MaterialTheme.typography.bodyMedium, color = contentColor)
                    Text(
                        text = block.spans.toAnnotated(onUrlClick, onMentionClick),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.CodeFenceBox(block: MdBlock.CodeFence, contentColor: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(contentColor.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
    ) {
        Column {
            if (block.lang != null) {
                Text(
                    block.lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                block.code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                color = contentColor,
            )
        }
    }
}

/** 行内 spans → AnnotatedString（mention 着胶囊底色，链接可点/着色下划线）。 */
@Composable
private fun List<MdSpan>.toAnnotated(
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((String) -> Unit)?,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val inlineCodeBg = LocalContentColor.current.copy(alpha = 0.12f)
    return buildAnnotatedString {
        forEach { span ->
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Styled -> {
                    pushStyle(
                        SpanStyle(
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                            fontStyle = if (span.italic) FontStyle.Italic else null,
                            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                            fontFamily = if (span.code) FontFamily.Monospace else null,
                            background = if (span.code) inlineCodeBg else Color.Unspecified,
                            fontSize = if (span.code) 13.sp else TextUnit.Unspecified,
                        )
                    )
                    append(span.text)
                    pop()
                }
                is MdSpan.Link -> {
                    if (onUrlClick != null) {
                        pushLink(LinkAnnotation.Clickable(tag = span.url) { onUrlClick(span.url) })
                    } else {
                        pushStyle(SpanStyle(color = linkColor))
                    }
                    append(span.label)
                    pop()
                }
                is MdSpan.Mention -> {
                    // mention 胶囊：主色文字 + 半透明底（点击进资料）
                    if (onMentionClick != null) {
                        pushLink(LinkAnnotation.Clickable(tag = span.uid) { onMentionClick(span.uid) })
                    }
                    pushStyle(
                        SpanStyle(
                            color = if (onMentionClick != null) linkColor else LocalContentColor.current,
                            background = linkColor.copy(alpha = 0.15f),
                        )
                    )
                    append("@${span.name}")
                    pop()
                    if (onMentionClick != null) pop()
                }
            }
        }
    }
}
