package com.virjar.tk.app.ui.component.rich

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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.theme.Tk

typealias EmbeddedAssetMarkdownContent = @Composable (
    asset: EmbeddedAsset,
    presentation: EmbeddedAssetPresentation,
    modifier: Modifier,
) -> Unit

/**
 * 富文本消息渲染（Markdown）——自研 Compose 渲染层。
 *
 * Markdown 先由 [MdParser] 投影成有界内部模型；此文件只负责把该模型映射为 Compose。
 * HTML、图片和未知扩展绝不执行或发起资源请求，而是以可读源码安全降级。
 */
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
    embeddedAssets: EmbeddedAssetRenderScope = EmbeddedAssetRenderScope.Empty,
    embeddedAssetContent: EmbeddedAssetMarkdownContent? = null,
) {
    val blocks = remember(content, embeddedAssets.cacheKey) { MdParser.parse(content, embeddedAssets) }
    val contentColor = LocalContentColor.current

    MarkdownBlocks(
        blocks = blocks,
        contentColor = contentColor,
        modifier = modifier,
        onUrlClick = onUrlClick,
        onMentionClick = onMentionClick,
        embeddedAssets = embeddedAssets,
        embeddedAssetContent = embeddedAssetContent,
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
    embeddedAssets: EmbeddedAssetRenderScope,
    embeddedAssetContent: EmbeddedAssetMarkdownContent?,
) {
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val topPadding = if (index == 0) 0.dp else Tk.spacing.xs
            when (block) {
                is MdBlock.Paragraph -> MarkdownSpanSequence(
                    spans = block.spans,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.padding(top = topPadding),
                    onUrlClick = onUrlClick,
                    onMentionClick = onMentionClick,
                    embeddedAssetContent = embeddedAssetContent,
                )
                is MdBlock.Heading -> MarkdownSpanSequence(
                    spans = block.spans,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = contentColor,
                    modifier = Modifier.padding(top = topPadding.coerceAtLeast(Tk.spacing.sm)),
                    onUrlClick = onUrlClick,
                    onMentionClick = onMentionClick,
                    embeddedAssetContent = embeddedAssetContent,
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
                        embeddedAssets = embeddedAssets,
                        embeddedAssetContent = embeddedAssetContent,
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
                    MarkdownSpanSequence(
                        spans = block.spans,
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        modifier = Modifier.padding(start = Tk.spacing.xs).weight(1f),
                        onUrlClick = onUrlClick,
                        onMentionClick = onMentionClick,
                        embeddedAssetContent = embeddedAssetContent,
                    )
                }
                is MdBlock.Table -> MarkdownTable(
                    block = block,
                    contentColor = contentColor,
                    modifier = Modifier.padding(top = topPadding),
                    onUrlClick = onUrlClick,
                    onMentionClick = onMentionClick,
                    embeddedAssets = embeddedAssets,
                    embeddedAssetContent = embeddedAssetContent,
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
    embeddedAssets: EmbeddedAssetRenderScope,
    embeddedAssetContent: EmbeddedAssetMarkdownContent?,
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
            embeddedAssets = embeddedAssets,
            embeddedAssetContent = embeddedAssetContent,
        )
        block.rows.forEach { row ->
            MarkdownTableRow(
                cells = row.padTo(columnCount, ""),
                alignments = alignments,
                header = false,
                contentColor = contentColor,
                onUrlClick = onUrlClick,
                onMentionClick = onMentionClick,
                embeddedAssets = embeddedAssets,
                embeddedAssetContent = embeddedAssetContent,
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
    embeddedAssets: EmbeddedAssetRenderScope,
    embeddedAssetContent: EmbeddedAssetMarkdownContent?,
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
                    embeddedAssets = embeddedAssets,
                    embeddedAssetContent = embeddedAssetContent,
                )
            }
        }
    }
}

private sealed interface MarkdownSpanSegment {
    data class Text(val spans: List<MdSpan>) : MarkdownSpanSegment
    data class Asset(val span: MdSpan.EmbeddedAsset) : MarkdownSpanSegment
}

private fun List<MdSpan>.toSegments(): List<MarkdownSpanSegment> = buildList {
    val text = mutableListOf<MdSpan>()
    fun flushText() {
        if (text.isNotEmpty()) {
            add(MarkdownSpanSegment.Text(text.toList()))
            text.clear()
        }
    }
    this@toSegments.forEach { span ->
        if (span is MdSpan.EmbeddedAsset) {
            flushText()
            add(MarkdownSpanSegment.Asset(span))
        } else {
            text += span
        }
    }
    flushText()
}

/** 块级媒体卡片按源码顺序与连续的带样式文本段交错排列。 */
@Composable
private fun MarkdownSpanSequence(
    spans: List<MdSpan>,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((String) -> Unit)?,
    embeddedAssetContent: EmbeddedAssetMarkdownContent?,
) {
    Column(
        modifier = modifier,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Tk.spacing.sm),
    ) {
        spans.toSegments().forEach { segment ->
            when (segment) {
                is MarkdownSpanSegment.Text -> Text(
                    text = segment.spans.toAnnotated(onUrlClick, onMentionClick),
                    style = style,
                    color = color,
                )
                is MarkdownSpanSegment.Asset -> {
                    val asset = segment.span
                    if (embeddedAssetContent != null) {
                        embeddedAssetContent(
                            asset.asset,
                            asset.presentation,
                            Modifier
                                .testTag(embeddedAssetRenderTestTag(asset.assetId, asset.presentation)),
                        )
                    } else {
                        Text(
                            text = asset.source,
                            style = style.copy(fontFamily = FontFamily.Monospace),
                            color = color,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(color.copy(alpha = 0.1f), MaterialTheme.shapes.small)
                                .padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.xs),
                        )
                    }
                }
            }
        }
    }
}

fun embeddedAssetRenderTestTag(
    assetId: String,
    presentation: EmbeddedAssetPresentation,
): String = "rich.asset.${presentation.name.lowercase()}.$assetId"

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
                is MdSpan.EmbeddedAsset -> append(span.source)
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
