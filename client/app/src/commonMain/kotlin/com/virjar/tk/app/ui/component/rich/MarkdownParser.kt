package com.virjar.tk.app.ui.component.rich

import com.virjar.tk.protocol.body.decodeCommonMarkPunctuationEscapes
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.model.EmbeddedAsset
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * 安全 Compose 渲染器使用的 JetBrains Markdown AST projection。
 *
 * 解析器专属的预算与语法处理保持独立于 Compose，因此畸形或历史超大的内容会在任何
 * UI 树创建之前被缩减为有界、无损的内部块模型。
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

    /** 仅针对本次解析提供的 manifest 作用域进行解析。 */
    data class EmbeddedAsset(
        val asset: com.virjar.tk.protocol.model.EmbeddedAsset,
        val presentation: EmbeddedAssetPresentation,
        /** 未安装平台渲染器时使用的精确源码。 */
        val source: String,
    ) : MdSpan() {
        val assetId: String get() = asset.assetId
    }

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
     * 这些上限由属于同一条消息的每次递归解析共享。为最终的 Raw 后缀保留一个块，
     * 使返回的树即使在顶层兄弟中途停止 projection 时也能保持在公开的块上限之内。
     */
    internal const val MAX_RENDERED_BLOCKS = 2_048
    internal const val MAX_RENDERED_NODES = 8_192
    internal const val MAX_RENDERED_SOURCE_WORK = 400_000L

    private class RenderBudget(val embeddedAssets: EmbeddedAssetRenderScope) {
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

    fun parse(
        src: String,
        embeddedAssets: EmbeddedAssetRenderScope = EmbeddedAssetRenderScope.Empty,
    ): List<MdBlock> {
        if (src.isEmpty()) return emptyList()
        val budget = RenderBudget(embeddedAssets)
        return try {
            parse(src, quoteDepth = 0, budget = budget, root = true)
        } catch (_: RenderBudgetExceeded) {
            // 根解析器本身可能在拥有可安装本地后缀的 AST 节点之前就耗尽 source-work 预算。
            // 这种情况下保留完整源码。
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
                // 保留已投影的兄弟节点，把失败节点与所有未访问的兄弟节点合并为一个精确的
                // Raw 后缀。这可以防止逐兄弟的 Raw 兜底击穿同一全局块预算。
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
                    val internalAssetId = dest?.let(::embeddedAssetIdOrNull)
                    when {
                        dest?.startsWith(TEAMTALK_ASSET_URI_PREFIX) == true -> {
                            val asset = internalAssetId?.let {
                                budget.embeddedAssets.resolve(it, EmbeddedAssetPresentation.FILE)
                            }
                            if (asset != null) {
                                out += MdSpan.EmbeddedAsset(
                                    asset = asset,
                                    presentation = EmbeddedAssetPresentation.FILE,
                                    source = node.getTextInNode(src).toString(),
                                )
                            } else {
                                out += MdSpan.Styled(
                                    text = node.getTextInNode(src).toString(),
                                    bold = style.bold,
                                    italic = style.italic,
                                    strike = style.strike,
                                    code = true,
                                )
                            }
                        }
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
                    val destination = node.embeddedAssetLinkDestination(src)
                    val internalAssetId = destination?.let(::embeddedAssetIdOrNull)
                    val asset = internalAssetId?.let {
                        budget.embeddedAssets.resolve(it, EmbeddedAssetPresentation.IMAGE)
                    }
                    if (asset != null) {
                        out += MdSpan.EmbeddedAsset(
                            asset = asset,
                            presentation = EmbeddedAssetPresentation.IMAGE,
                            source = node.getTextInNode(src).toString(),
                        )
                    } else {
                        // 外部、畸形与超出作用域的图片保持为惰性源码。它们不会到达
                        // 平台图片加载器或普通 URL 点击回调。
                        out += MdSpan.Styled(
                            text = node.getTextInNode(src).toString(),
                            bold = style.bold,
                            italic = style.italic,
                            strike = style.strike,
                            code = true,
                        )
                    }
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
