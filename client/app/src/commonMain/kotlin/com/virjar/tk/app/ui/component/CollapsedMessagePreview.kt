package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.app.ui.component.rich.MdBlock
import com.virjar.tk.app.ui.component.rich.MdParser
import com.virjar.tk.app.ui.component.rich.MdSpan

/** 先截取再解析：气泡不会为长日志构建完整 Markdown AST、图片或文本布局。 */
internal fun collapsedMessagePreview(body: MessageBody?): String? {
    val markdown = when (body) {
        is RichTextBody -> body.markdown
        is ReplyBody -> body.content
        else -> return null
    }
    if (markdown.length <= 600 && markdown.count { it == '\n' } +
        Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).findAll(markdown).count() < 12) return null
    var end = minOf(240, markdown.length)
    if (end < markdown.length && end > 0 && markdown[end - 1].isHighSurrogate()) end--
    val preview = MdParser.parse(markdown.substring(0, end), embeddedAssetRenderScopeFor(body)).joinToString("\n", transform = ::previewText)
    return preview.trimEnd() + "…"
}

private fun previewText(block: MdBlock): String = when (block) {
    is MdBlock.Paragraph -> block.spans.joinToString("", transform = ::previewText)
    is MdBlock.Heading -> block.spans.joinToString("", transform = ::previewText)
    is MdBlock.ListItem -> block.markerText + " " + block.spans.joinToString("", transform = ::previewText)
    is MdBlock.CodeFence -> block.code
    is MdBlock.Quote -> block.blocks.joinToString("\n", transform = ::previewText)
    is MdBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { it.joinToString(" | ") }
    is MdBlock.Raw -> block.source
    MdBlock.HorizontalRule -> "—"
}
private fun previewText(span: MdSpan): String = when (span) {
    is MdSpan.Text -> span.text
    is MdSpan.Styled -> span.text
    is MdSpan.Link -> span.label
    is MdSpan.Mention -> "@" + span.name
    is MdSpan.EmbeddedAsset -> if (span.presentation == com.virjar.tk.protocol.body.EmbeddedAssetPresentation.IMAGE) "[图片]" else "[文件]"
}
