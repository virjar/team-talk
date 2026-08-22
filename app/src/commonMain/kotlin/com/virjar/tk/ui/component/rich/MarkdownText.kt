package com.virjar.tk.ui.component.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.body.decodeCommonMarkPunctuationEscapes
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
 * 选型（doc/05-clients/rich-content.md）：只用 JetBrains 官方 parser（`org.jetbrains:markdown`，
 * 纯 Kotlin 无传递依赖），AST → 块模型 → AnnotatedString/Compose 组件自行渲染。
 * 放弃 mikepenz 渲染器：其 JVM 字节码为 Java 21（class 65），本项目运行时 JBR 17 只认 61，
 * 运行期 UnsupportedClassVersionError（F17）；且纯 Text 方案没有 inlineContent（mention 胶囊/卡片受限）。
 *
 * 支持子集（IM 场景）：段落/粗体/斜体/删除线/行内代码/链接（含 mention://）/代码块/标题/
 * 列表/任务列表/递归引用/GFM 表格/分隔线。HTML、图片和未知扩展绝不执行或发起资源请求，
 * 而是以可读源码安全降级。
 * 普通文本无语法时视觉等同纯文本。颜色全部取自气泡 LocalContentColor（蓝/灰气泡自适应）。
 */

// ── 块/行内模型（parser AST 的稳定投影，渲染层不触 AST） ──

internal sealed class MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class CodeFence(val lang: String?, val code: String) : MdBlock()
    data class Quote(val blocks: List<MdBlock>) : MdBlock()
    data class Table(
        val headers: List<String>,
        val alignments: List<DocumentTableAlignment>,
        val rows: List<List<String>>,
    ) : MdBlock()

    data object HorizontalRule : MdBlock()

    /** 未建模或有主动内容风险的 Markdown 原文；只读显示，绝不当 HTML/媒体执行。 */
    data class Raw(val source: String) : MdBlock()

    data class ListItem(
        val spans: List<MdSpan>,
        val kind: MdListKind,
        /** 根列表为 0；每进入一层嵌套列表递增 1。 */
        val depth: Int,
        /** 仅有序列表有值，并保留 Markdown 源码中的显式序号。 */
        val number: Int? = null,
        /** GFM 任务列表状态；null 表示普通列表项。 */
        val taskChecked: Boolean? = null,
    ) : MdBlock() {
        init {
            require(depth >= 0) { "List depth must not be negative" }
            require((kind == MdListKind.Ordered) == (number != null)) {
                "Only ordered list items have a number"
            }
        }

        val markerText: String
            get() = when (kind) {
                MdListKind.Unordered -> "•"
                MdListKind.Ordered -> "$number."
            }
    }
}

internal enum class MdListKind { Ordered, Unordered }

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
    /**
     * 服务端会拒绝超出这些预算的新消息；渲染端仍需独立防御历史数据、旧客户端与本地缓存。
     * 超限内容降级为可复制源码，而不是继续递归或一次性组合成千上万个单元格。
     */
    private const val MAX_RENDERED_QUOTE_DEPTH = 64
    private const val MAX_RENDERED_AST_DEPTH = 128
    private const val MAX_RENDERED_TABLE_COLUMNS = 32
    private const val MAX_RENDERED_TABLE_CELLS = 1_000

    /**
     * These limits are shared by every recursive parse that belongs to one message. Reserving one
     * block for the final Raw suffix keeps the returned tree inside the advertised block ceiling
     * even when projection stops part-way through a top-level sibling.
     */
    internal const val MAX_RENDERED_BLOCKS = 2_048
    internal const val MAX_RENDERED_NODES = 8_192
    internal const val MAX_RENDERED_SOURCE_WORK = 400_000L

    private class RenderBudget {
        private var blocksRemaining = MAX_RENDERED_BLOCKS - 1
        private var nodesRemaining = MAX_RENDERED_NODES
        private var sourceWorkRemaining = MAX_RENDERED_SOURCE_WORK

        fun consumeBlock() {
            if (blocksRemaining <= 0) throw RenderBudgetExceeded()
            blocksRemaining -= 1
        }

        fun consumeNodes(count: Int = 1) {
            if (count < 0 || count > nodesRemaining) throw RenderBudgetExceeded()
            nodesRemaining -= count
        }

        fun consumeSourceWork(characters: Int) {
            val work = characters.toLong()
            if (work < 0L || work > sourceWorkRemaining) throw RenderBudgetExceeded()
            sourceWorkRemaining -= work
        }
    }

    private class RenderBudgetExceeded : RuntimeException()

    // GFM：在 CommonMark 基础上提供删除线、任务列表和表格。
    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    private val listContainerTypes: Set<org.intellij.markdown.IElementType> = setOf(
        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.ORDERED_LIST,
    )

    /** 语法标记 token（星号、波浪号、反引号、方圆括号等）：只参与结构不产出文本，逐出渲染（曾泄漏为可见字符） */
    private val markerTokens: Set<org.intellij.markdown.IElementType> = setOf(
        MarkdownTokenTypes.EMPH, MarkdownTokenTypes.BACKTICK, MarkdownTokenTypes.ESCAPED_BACKTICKS,
        MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.EXCLAMATION_MARK, MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.LT, MarkdownTokenTypes.GT,
        GFMTokenTypes.TILDE,
        GFMTokenTypes.CHECK_BOX,
        MarkdownTokenTypes.SINGLE_QUOTE, MarkdownTokenTypes.DOUBLE_QUOTE,
        MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER,
    )

    fun parse(src: String): List<MdBlock> {
        if (src.isEmpty()) return emptyList()
        val budget = RenderBudget()
        return try {
            parse(src, quoteDepth = 0, budget = budget, root = true)
        } catch (_: RenderBudgetExceeded) {
            // The root parser itself can consume the source-work budget before it has an AST node
            // at which to install a local suffix. Preserve the complete source in that case.
            listOf(MdBlock.Raw(src))
        }
    }

    private fun parse(
        src: String,
        quoteDepth: Int,
        budget: RenderBudget,
        root: Boolean = false,
    ): List<MdBlock> {
        budget.consumeSourceWork(src.length)
        val children = parser.buildMarkdownTreeFromString(src).children
        if (!root) {
            return children.flatMap { node ->
                node.toBlocks(src, listDepth = 0, quoteDepth = quoteDepth, budget = budget)
            }
        }

        val blocks = mutableListOf<MdBlock>()
        var renderedUntil = 0
        for (node in children) {
            try {
                blocks += node.toBlocks(src, listDepth = 0, quoteDepth = quoteDepth, budget = budget)
                renderedUntil = node.endOffset.coerceIn(renderedUntil, src.length)
            } catch (_: RenderBudgetExceeded) {
                // Keep already-projected siblings and coalesce the failing node plus every
                // unvisited sibling into one exact Raw suffix. This prevents a Raw-per-sibling
                // fallback from defeating the same global block budget.
                val suffix = src.substring(renderedUntil)
                if (suffix.isNotEmpty()) blocks += MdBlock.Raw(suffix)
                break
            }
        }
        return blocks
    }

    /** 列表容器递归投影为带类型、显式序号和层级的平铺行；其余块类型一一映射。 */
    private fun ASTNode.toBlocks(
        src: String,
        listDepth: Int,
        quoteDepth: Int,
        budget: RenderBudget,
    ): List<MdBlock> {
        budget.consumeNodes()
        return when (type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST ->
                toListBlocks(src, listDepth, quoteDepth, budget)
            else -> listOfNotNull(toBlock(src, quoteDepth, budget))
        }
    }

    private fun ASTNode.toListBlocks(
        src: String,
        depth: Int,
        quoteDepth: Int,
        budget: RenderBudget,
    ): List<MdBlock> {
        if (depth >= MAX_RENDERED_AST_DEPTH) {
            budget.consumeBlock()
            return listOf(MdBlock.Raw(getTextInNode(src).toString()))
        }
        val kind = when (type) {
            MarkdownElementTypes.ORDERED_LIST -> MdListKind.Ordered
            else -> MdListKind.Unordered
        }
        return buildList {
            children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.forEach { item ->
                budget.consumeNodes()
                val taskChecked = item.explicitTaskState(src)
                val spans = item.inline(
                    src = src,
                    literalMarkers = false,
                    excludeNestedLists = true,
                    budget = budget,
                ).trimListStructureTail().withoutTaskPrefix(taskChecked != null)
                budget.consumeBlock()
                add(
                    MdBlock.ListItem(
                        spans = spans,
                        kind = kind,
                        depth = depth,
                        number = if (kind == MdListKind.Ordered) {
                            item.explicitListNumber(src) ?: 1
                        } else {
                            null
                        },
                        taskChecked = taskChecked,
                    )
                )
                item.children
                    .filter { it.type in listContainerTypes }
                    .forEach { nested ->
                        addAll(nested.toBlocks(src, depth + 1, quoteDepth, budget))
                    }
            }
        }
    }

    private fun ASTNode.explicitListNumber(src: String): Int? {
        // LIST_NUMBER 在不同嵌套深度下并不总是 LIST_ITEM 的可检索 AST 后代；
        // 列表项自身首行才是序号的权威来源，也不会误读到更深层子列表。
        val firstLine = getTextInNode(src).toString().lineSequence().firstOrNull()?.trimStart().orEmpty()
        val digits = firstLine.takeWhile(Char::isDigit)
        if (digits.isEmpty() || firstLine.getOrNull(digits.length) !in setOf('.', ')')) return null
        return digits.toIntOrNull()
    }

    private fun ASTNode.explicitTaskState(src: String): Boolean? {
        val firstLine = getTextInNode(src).toString().lineSequence().firstOrNull().orEmpty()
        val markerEnd = Regex("""^\s*(?:[-+*]|\d+[.)])\s+\[([ xX])]\s*""")
            .find(firstLine)
            ?: return null
        return markerEnd.groupValues[1].equals("x", ignoreCase = true)
    }

    /** 某些 parser 版本把 CHECK_BOX 投影为文本叶子；统一剥离，避免显示成 "[x] 正文"。 */
    private fun List<MdSpan>.withoutTaskPrefix(isTask: Boolean): List<MdSpan> {
        if (!isTask) return this
        val result = toMutableList()
        val pattern = Regex("""^\s*\[[ xX]]\s*""")
        for (index in result.indices) {
            val span = result[index]
            val text = when (span) {
                is MdSpan.Text -> span.text
                is MdSpan.Styled -> span.text
                else -> continue
            }
            val match = pattern.find(text) ?: break
            val stripped = text.removeRange(match.range)
            result[index] = when (span) {
                is MdSpan.Text -> span.copy(text = stripped)
                is MdSpan.Styled -> span.copy(text = stripped)
            }
            if (stripped.isEmpty()) result.removeAt(index)
            break
        }
        return result
    }

    /** 移除嵌套列表前 AST 留在父项中的换行与缩进，不触碰正文内部空白。 */
    private fun List<MdSpan>.trimListStructureTail(): List<MdSpan> {
        val result = toMutableList()
        while (result.isNotEmpty()) {
            val last = result.last()
            val trimmed = when (last) {
                is MdSpan.Text -> last.text.trimEnd().let { MdSpan.Text(it) }
                is MdSpan.Styled -> last.copy(text = last.text.trimEnd())
                else -> return result
            }
            val empty = when (trimmed) {
                is MdSpan.Text -> trimmed.text.isEmpty()
                is MdSpan.Styled -> trimmed.text.isEmpty()
                else -> false
            }
            if (empty) result.removeAt(result.size - 1) else {
                result[result.lastIndex] = trimmed
                break
            }
        }
        return result
    }

    private fun ASTNode.toBlock(src: String, quoteDepth: Int, budget: RenderBudget): MdBlock? = when (type) {
        MarkdownElementTypes.PARAGRAPH -> {
            val spans = inline(src, budget = budget)
            budget.consumeBlock()
            MdBlock.Paragraph(spans)
        }
        MarkdownElementTypes.ATX_1 -> {
            val spans = inline(src, budget = budget).withoutHeadingPrefix()
            budget.consumeBlock()
            MdBlock.Heading(1, spans)
        }
        MarkdownElementTypes.ATX_2 -> {
            val spans = inline(src, budget = budget).withoutHeadingPrefix()
            budget.consumeBlock()
            MdBlock.Heading(2, spans)
        }
        MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val spans = inline(src, budget = budget).withoutHeadingPrefix()
            budget.consumeBlock()
            MdBlock.Heading(3, spans)
        }
        MarkdownElementTypes.CODE_FENCE -> {
            val raw = getTextInNode(src).toString()
            budget.consumeBlock()
            budget.consumeSourceWork(raw.length)
            DocumentMarkdownBlockCodec.parse(raw)
                .filterIsInstance<DocumentCodeFenceBlock>()
                .singleOrNull()
                ?.let { MdBlock.CodeFence(lang = it.language, code = it.code) }
                ?: MdBlock.Raw(raw)
        }
        MarkdownElementTypes.CODE_BLOCK -> {
            budget.consumeBlock()
            MdBlock.CodeFence(lang = null, code = getTextInNode(src).toString().trimEnd('\n'))
        }
        MarkdownElementTypes.BLOCK_QUOTE -> {
            val raw = getTextInNode(src).toString()
            budget.consumeBlock()
            if (quoteDepth >= MAX_RENDERED_QUOTE_DEPTH) {
                MdBlock.Raw(raw)
            } else {
                budget.consumeSourceWork(raw.length)
                val inner = DocumentMarkdownBlockCodec.parse(raw)
                    .filterIsInstance<DocumentQuoteBlock>()
                    .singleOrNull()
                    ?.innerMarkdown
                if (inner != null) {
                    MdBlock.Quote(parse(inner, quoteDepth + 1, budget))
                } else {
                    MdBlock.Raw(raw)
                }
            }
        }
        GFMElementTypes.TABLE -> {
            val raw = getTextInNode(src).toString()
            budget.consumeBlock()
            budget.consumeSourceWork(raw.length)
            DocumentMarkdownBlockCodec.parse(raw)
                .filterIsInstance<DocumentGfmTableBlock>()
                .singleOrNull()
                ?.takeIf { table ->
                    val columns = maxOf(
                        table.headers.size,
                        table.alignments.size,
                        table.rows.maxOfOrNull(List<String>::size) ?: 0,
                    )
                    val cells = columns.toLong() * (table.rows.size + 1L)
                    columns <= MAX_RENDERED_TABLE_COLUMNS && cells <= MAX_RENDERED_TABLE_CELLS
                }
                ?.let { table ->
                    val projectedCells = maxOf(
                        table.headers.size,
                        table.alignments.size,
                        table.rows.maxOfOrNull(List<String>::size) ?: 0,
                    ) * (table.rows.size + 1)
                    budget.consumeNodes(projectedCells)
                    MdBlock.Table(table.headers, table.alignments, table.rows)
                }
                ?: MdBlock.Raw(raw)
        }
        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            budget.consumeBlock()
            MdBlock.HorizontalRule
        }
        else -> getTextInNode(src).toString()
            .takeIf(String::isNotBlank)
            ?.let { raw ->
                budget.consumeBlock()
                MdBlock.Raw(raw)
            }
    }

    /**
     * @param literalMarkers 段落层孤立的 marker token（未闭合语法的 `**` 等）是否字面显示。
     *   段落内 true（保序不丢字）；列表项 false（`- ` 是结构不是内容）。
     */
    private fun ASTNode.inline(
        src: String,
        literalMarkers: Boolean = true,
        excludeNestedLists: Boolean = false,
        budget: RenderBudget,
    ): List<MdSpan> {
        val out = mutableListOf<MdSpan>()

        /**
         * @param inStructure 当前是否处于语法结构内（EMPH/STRONG/LINK/CODE_SPAN 子树）。
         *   marker token 在结构内 = 语法符号（逐出）；在段落层孤立出现 = 未闭合语法的字面字符（保留，不丢字）。
         */
        fun walk(node: ASTNode, style: MdSpan.Styled, inStructure: Boolean, depth: Int) {
            if (excludeNestedLists && node.type in listContainerTypes) return
            budget.consumeNodes()
            if (depth >= MAX_RENDERED_AST_DEPTH) {
                // 历史/旧客户端数据可能未经过服务端结构预算。保留剩余子树的可读源码，
                // 不再递归 Compose/AST，从而避免列表内深层引用或扩展语法耗尽调用栈。
                out += MdSpan.Styled(
                    text = node.getTextInNode(src).toString(),
                    bold = style.bold,
                    italic = style.italic,
                    strike = style.strike,
                    code = true,
                )
                return
            }
            when (node.type) {
                MarkdownElementTypes.EMPH -> node.children.forEach { walk(it, style.copy(italic = true), true, depth + 1) }
                MarkdownElementTypes.STRONG -> node.children.forEach { walk(it, style.copy(bold = true), true, depth + 1) }
                GFMElementTypes.STRIKETHROUGH -> node.children.forEach { walk(it, style.copy(strike = true), true, depth + 1) }
                MarkdownElementTypes.CODE_SPAN -> out += MdSpan.Styled(
                    node.getTextInNode(src).toString().trim('`'),
                    bold = style.bold, italic = style.italic, strike = style.strike, code = true,
                )
                MarkdownElementTypes.INLINE_LINK -> {
                    val dest = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                        ?.getTextInNode(src)?.toString()
                        ?.let(::decodeCommonMarkPunctuationEscapes)
                    // LINK_TEXT 的源码切片包含方括号；整体解码才能保留空格与被转义的 `]`。
                    // 只拼 TEXT 叶子会漏掉 WHITE_SPACE，并把 `文档 [v2]` 错渲染为 `文档[v2]`。
                    val label = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                        ?.getTextInNode(src)
                        ?.toString()
                        ?.removeSurrounding("[", "]")
                        ?.let(::decodeCommonMarkPunctuationEscapes)
                        .orEmpty()
                    when {
                        dest != null && dest.startsWith("mention://") -> {
                            // 全角标点后 parser 会把 @ 并进 LINK_TEXT（半角空格后则是独立叶子），
                            // 统一去前导 @ 防止渲染 @@（后处理只覆盖独立叶子场景）
                            val name = label.ifBlank { dest }.removePrefix("@")
                            out += MdSpan.Mention(uid = dest.removePrefix("mention://"), name = name)
                        }
                        dest != null -> out += MdSpan.Link(label = label.ifBlank { dest }, url = dest)
                        else -> out += MdSpan.Styled(label.ifBlank { node.getTextInNode(src).toString() }, style.bold, style.italic, style.strike, style.code)
                    }
                }
                MarkdownElementTypes.AUTOLINK -> {
                    val url = node.getTextInNode(src).toString().trim('<', '>')
                    out += MdSpan.Link(label = url, url = url)
                }
                MarkdownElementTypes.IMAGE -> {
                    // 消息 Markdown 不加载图片 URL。原始语法可读、可复制，也不会被误当作链接点击。
                    out += MdSpan.Styled(
                        text = node.getTextInNode(src).toString(),
                        bold = style.bold,
                        italic = style.italic,
                        strike = style.strike,
                        code = true,
                    )
                }
                else -> {
                    // 叶子：语法标记 token 逐出，正文原样保留（保序不丢字）；
                    // 无样式叠加时产出 Text（而非空 Styled，纯文本零包装）
                    if (node.children.isEmpty()) {
                        if (node.type !in markerTokens || !inStructure) {
                            val text = decodeCommonMarkPunctuationEscapes(node.getTextInNode(src).toString())
                            if (text.isNotEmpty()) {
                                out += if (style.bold || style.italic || style.strike || style.code) {
                                    style.copy(text = text)
                                } else {
                                    MdSpan.Text(text)
                                }
                            }
                        }
                    } else {
                        node.children.forEach { walk(it, style, inStructure, depth + 1) }
                    }
                }
            }
        }
        children.forEach { walk(it, MdSpan.Styled(""), inStructure = !literalMarkers, depth = 0) }

        // `@[名](mention://uid)` 的 `@` 会被 parser 并进前置文本（"完成！@" 或独立 "@" 叶子），
        // Mention 渲染再补 "@" 会输出 "@@名"（曾现连续双 @）——剥离紧邻前节点尾部的一个 "@"
        val result = out.toMutableList()
        var i = 1
        while (i < result.size) {
            if (result[i] is MdSpan.Mention) {
                when (val prev = result[i - 1]) {
                    is MdSpan.Text -> if (prev.text.endsWith("@")) {
                        result[i - 1] = prev.copy(text = prev.text.dropLast(1))
                    }
                    is MdSpan.Styled -> if (prev.text.endsWith("@")) {
                        result[i - 1] = prev.copy(text = prev.text.dropLast(1))
                    }
                    else -> {}
                }
                val stripped = result[i - 1]
                val empty = (stripped is MdSpan.Text && stripped.text.isEmpty()) ||
                    (stripped is MdSpan.Styled && stripped.text.isEmpty())
                if (empty) result.removeAt(i - 1)
            }
            i++
        }
        return result
    }

    /** ATX 标题的 `# ` 会被 parser 作为普通叶子暴露；它属于块结构，不应进入预览文本。 */
    private fun List<MdSpan>.withoutHeadingPrefix(): List<MdSpan> {
        val result = toMutableList()
        var markerSeen = false
        while (result.isNotEmpty()) {
            val first = result.first() as? MdSpan.Text ?: break
            val stripped = if (markerSeen) {
                first.text.trimStart()
            } else {
                val withoutHashes = first.text.dropWhile { it == '#' }
                if (withoutHashes.length == first.text.length) break
                markerSeen = true
                withoutHashes.trimStart()
            }
            if (stripped.isEmpty()) {
                result.removeAt(0)
            } else {
                result[0] = first.copy(text = stripped)
                break
            }
        }
        return result
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

    MarkdownBlocks(
        blocks = blocks,
        contentColor = contentColor,
        modifier = modifier,
        onUrlClick = onUrlClick,
        onMentionClick = onMentionClick,
    )
}

/** 同一个递归渲染入口确保引用中的列表、代码、表格等不会被静默丢弃。 */
@Composable
private fun MarkdownBlocks(
    blocks: List<MdBlock>,
    contentColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((uid: String) -> Unit)?,
) {
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val topPadding = if (index == 0) 0.dp else Tk.spacing.xs
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = block.spans.toAnnotated(onUrlClick, onMentionClick),
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.padding(top = topPadding),
                )
                is MdBlock.Heading -> Text(
                    text = block.spans.toAnnotated(onUrlClick, onMentionClick),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = contentColor,
                    modifier = Modifier.padding(top = topPadding.coerceAtLeast(Tk.spacing.sm)),
                )
                is MdBlock.CodeFence -> CodeFenceBox(
                    block = block,
                    contentColor = contentColor,
                    modifier = Modifier.padding(top = topPadding),
                )
                is MdBlock.Quote -> Row(Modifier.padding(top = topPadding).height(IntrinsicSize.Min)) {
                    // 引用块：与回复卡片同语言的左侧竖线
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(contentColor.copy(alpha = 0.4f)),
                    )
                    Spacer(Modifier.width(Tk.spacing.sm))
                    MarkdownBlocks(
                        blocks = block.blocks,
                        contentColor = contentColor.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onUrlClick = onUrlClick,
                        onMentionClick = onMentionClick,
                    )
                }
                is MdBlock.ListItem -> Row(Modifier.padding(top = topPadding)) {
                    if (block.taskChecked != null) {
                        ReadOnlyTaskCheckbox(
                            checked = block.taskChecked,
                            contentColor = contentColor,
                            modifier = Modifier
                                .padding(start = (block.depth * 16).dp)
                                .width(28.dp)
                                .height(24.dp),
                        )
                    } else {
                        Text(
                            text = block.markerText,
                            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .padding(start = (block.depth * 16).dp)
                                .width(28.dp),
                        )
                    }
                    Text(
                        text = block.spans.toAnnotated(onUrlClick, onMentionClick),
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        modifier = Modifier.padding(start = Tk.spacing.xs),
                    )
                }
                is MdBlock.Table -> MarkdownTable(
                    block = block,
                    contentColor = contentColor,
                    modifier = Modifier.padding(top = topPadding),
                    onUrlClick = onUrlClick,
                    onMentionClick = onMentionClick,
                )
                MdBlock.HorizontalRule -> Spacer(
                    Modifier
                        .padding(top = topPadding.coerceAtLeast(Tk.spacing.sm))
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(contentColor.copy(alpha = 0.3f)),
                )
                is MdBlock.Raw -> RawMarkdownBox(
                    source = block.source,
                    contentColor = contentColor,
                    modifier = Modifier.padding(top = topPadding),
                )
            }
        }
    }
}

/** 紧凑、不可编辑，但保留 Checkbox role/state，辅助技术不会把它误报为普通装饰图标。 */
@Composable
private fun ReadOnlyTaskCheckbox(
    checked: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.5.dp, contentColor.copy(alpha = 0.72f), MaterialTheme.shapes.extraSmall)
                .background(
                    color = if (checked) contentColor.copy(alpha = 0.92f) else Color.Transparent,
                    shape = MaterialTheme.shapes.extraSmall,
                )
                .semantics {
                    role = Role.Checkbox
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                    disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    color = if (contentColor.luminance() > 0.5f) Color.Black else Color.White,
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun CodeFenceBox(
    block: MdBlock.CodeFence,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
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

@Composable
private fun MarkdownTable(
    block: MdBlock.Table,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((String) -> Unit)?,
) {
    val columnCount = maxOf(
        1,
        block.headers.size,
        block.alignments.size,
        block.rows.maxOfOrNull(List<String>::size) ?: 0,
    )
    val headers = block.headers.padTo(columnCount, "")
    val alignments = block.alignments.padTo(columnCount, DocumentTableAlignment.NONE)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, contentColor.copy(alpha = 0.24f), MaterialTheme.shapes.small),
    ) {
        MarkdownTableRow(
            cells = headers,
            alignments = alignments,
            header = true,
            contentColor = contentColor,
            onUrlClick = onUrlClick,
            onMentionClick = onMentionClick,
        )
        block.rows.forEach { row ->
            MarkdownTableRow(
                cells = row.padTo(columnCount, ""),
                alignments = alignments,
                header = false,
                contentColor = contentColor,
                onUrlClick = onUrlClick,
                onMentionClick = onMentionClick,
            )
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    alignments: List<DocumentTableAlignment>,
    header: Boolean,
    contentColor: Color,
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((String) -> Unit)?,
) {
    Row {
        cells.forEachIndexed { index, cell ->
            val alignment = when (alignments.getOrNull(index)) {
                DocumentTableAlignment.CENTER -> Alignment.Center
                DocumentTableAlignment.RIGHT -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }
            Box(
                modifier = Modifier
                    .width(144.dp)
                    .heightIn(min = 40.dp)
                    .border(0.5.dp, contentColor.copy(alpha = 0.2f))
                    .background(
                        if (header) contentColor.copy(alpha = 0.12f)
                        else Color.Transparent,
                    ),
                contentAlignment = alignment,
            ) {
                MarkdownText(
                    content = decodeTableCellForMessage(cell),
                    modifier = Modifier.padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.xs),
                    onUrlClick = onUrlClick,
                    onMentionClick = onMentionClick,
                )
            }
        }
    }
}

@Composable
private fun RawMarkdownBox(
    source: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = source,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = contentColor,
        modifier = modifier
            .fillMaxWidth()
            .background(contentColor.copy(alpha = 0.1f), MaterialTheme.shapes.small)
            .padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.xs),
    )
}

private fun <T> List<T>.padTo(size: Int, value: T): List<T> =
    this + List((size - this.size).coerceAtLeast(0)) { value }

/** GFM 表格里的 `\|` 是存储转义，视觉内容应显示为普通竖线。 */
private fun decodeTableCellForMessage(markdown: String): String = buildString(markdown.length) {
    var index = 0
    while (index < markdown.length) {
        if (markdown[index] == '\\' && markdown.getOrNull(index + 1) == '|') {
            append('|')
            index += 2
        } else {
            append(markdown[index])
            index++
        }
    }
}

/** 行内 spans → AnnotatedString（mention 着胶囊底色，链接可点/着色下划线）。 */
@Composable
private fun List<MdSpan>.toAnnotated(
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((String) -> Unit)?,
): AnnotatedString {
    // 深色表面上的浅色正文不能继续使用 primary 蓝；链接/提及改用白色系（F20）。
    val onDarkSurface = LocalContentColor.current.luminance() > 0.5f
    val linkColor = if (onDarkSurface) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val mentionBg = if (onDarkSurface) Color.White.copy(alpha = 0.2f)
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
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
                    // LinkAnnotation 自带默认链接样式（蓝），会覆盖外层色——pushLink 后必须
                    // 再显式 pushStyle（自适应色 + 下划线），否则蓝气泡上链接不可见（F22）
                    if (onUrlClick != null) {
                        pushLink(LinkAnnotation.Clickable(tag = span.url) { onUrlClick(span.url) })
                    }
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(span.label)
                    pop()
                    if (onUrlClick != null) pop()
                }
                is MdSpan.Mention -> {
                    // mention 胶囊：主色文字 + 半透明底（点击进资料）
                    if (onMentionClick != null) {
                        pushLink(LinkAnnotation.Clickable(tag = span.uid) { onMentionClick(span.uid) })
                    }
                    pushStyle(
                        SpanStyle(
                            color = if (onMentionClick != null) linkColor else LocalContentColor.current,
                            background = mentionBg,
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
