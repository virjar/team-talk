package com.virjar.tk.ui.component

import com.virjar.tk.model.Attachment

/** 聊天文件附件可在应用内安全预览的文本类型。 */
enum class TextAttachmentPreviewKind {
    PLAIN_TEXT,
    MARKDOWN,
}

/**
 * 单次内嵌预览的硬上限。
 *
 * 文件下载仍走平台的流式磁盘缓存；只有不超过此上限的字节才会进入 String 和 Markdown
 * 解析器，避免把普通文件附件入口变成大文件内存放大器。
 */
const val MAX_TEXT_ATTACHMENT_PREVIEW_BYTES: Long = 512L * 1024L

sealed interface TextAttachmentPreviewPlan {
    data class Preview(val kind: TextAttachmentPreviewKind) : TextAttachmentPreviewPlan
    data object UseExternalApplication : TextAttachmentPreviewPlan
    data class TooLarge(val actualBytes: Long, val maxBytes: Long) : TextAttachmentPreviewPlan
    data class UnsupportedCharset(val charset: String) : TextAttachmentPreviewPlan
    data class InvalidSize(val actualBytes: Long) : TextAttachmentPreviewPlan
}

sealed interface TextAttachmentPreviewState {
    data object Loading : TextAttachmentPreviewState
    data class Ready(
        val kind: TextAttachmentPreviewKind,
        val content: String,
    ) : TextAttachmentPreviewState

    data class Failed(val message: String) : TextAttachmentPreviewState
    data class TooLarge(val maxBytes: Long) : TextAttachmentPreviewState
    data class UnsupportedCharset(val charset: String) : TextAttachmentPreviewState
}

/**
 * MIME 是服务端保存的附件元数据，优先于文件名；只有缺失或通用二进制 MIME 才按最终扩展名兜底。
 * 因而 `note.txt.exe` 不会仅凭中间扩展名被当作文本执行路径。
 */
fun textAttachmentPreviewKind(attachment: Attachment): TextAttachmentPreviewKind? {
    val mime = attachment.contentType.substringBefore(';').trim().lowercase()
    return when (mime) {
        "text/markdown", "text/x-markdown", "application/markdown" ->
            TextAttachmentPreviewKind.MARKDOWN

        "text/plain" -> TextAttachmentPreviewKind.PLAIN_TEXT
        "", "application/octet-stream", "binary/octet-stream", "application/binary" ->
            textAttachmentKindFromFileName(attachment.name)

        else -> null
    }
}

fun textAttachmentPreviewPlan(
    attachment: Attachment,
    maxBytes: Long = MAX_TEXT_ATTACHMENT_PREVIEW_BYTES,
): TextAttachmentPreviewPlan {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val kind = textAttachmentPreviewKind(attachment)
        ?: return TextAttachmentPreviewPlan.UseExternalApplication
    if (attachment.size < 0L) return TextAttachmentPreviewPlan.InvalidSize(attachment.size)
    if (attachment.size > maxBytes) {
        return TextAttachmentPreviewPlan.TooLarge(attachment.size, maxBytes)
    }
    val charset = attachment.contentType
        .split(';')
        .drop(1)
        .mapNotNull { parameter ->
            val key = parameter.substringBefore('=', missingDelimiterValue = "").trim()
            if (!key.equals("charset", ignoreCase = true)) return@mapNotNull null
            parameter.substringAfter('=', missingDelimiterValue = "")
                .trim()
                .trim('"', '\'')
                .takeIf { it.isNotEmpty() }
        }
        .firstOrNull()
    if (charset != null && !charset.equals("utf-8", ignoreCase = true) &&
        !charset.equals("utf8", ignoreCase = true)
    ) {
        return TextAttachmentPreviewPlan.UnsupportedCharset(charset)
    }
    return TextAttachmentPreviewPlan.Preview(kind)
}

/** 严格 UTF-8 解码；剥离 BOM，并拒绝会破坏文本/语义展示的二进制控制字符。 */
fun decodeTextAttachmentPreview(
    bytes: ByteArray,
    kind: TextAttachmentPreviewKind,
    maxBytes: Long = MAX_TEXT_ATTACHMENT_PREVIEW_BYTES,
): TextAttachmentPreviewState.Ready {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    require(bytes.size.toLong() <= maxBytes) { "文件超过应用内预览上限" }
    val start = if (
        bytes.size >= UTF8_BOM.size &&
        UTF8_BOM.indices.all { index -> bytes[index] == UTF8_BOM[index] }
    ) {
        UTF8_BOM.size
    } else {
        0
    }
    val content = bytes.decodeToString(
        startIndex = start,
        endIndex = bytes.size,
        throwOnInvalidSequence = true,
    )
    require(content.none(::isUnsafeTextControl)) { "文件包含无法安全显示的控制字符" }
    return TextAttachmentPreviewState.Ready(kind = kind, content = content)
}

private fun textAttachmentKindFromFileName(rawName: String): TextAttachmentPreviewKind? {
    val name = rawName.substringBefore('?').substringBefore('#')
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "txt" -> TextAttachmentPreviewKind.PLAIN_TEXT
        "md", "markdown", "mdown", "mkd" -> TextAttachmentPreviewKind.MARKDOWN
        else -> null
    }
}

private fun isUnsafeTextControl(char: Char): Boolean =
    char == '\u007f' || (char < ' ' && char != '\n' && char != '\r' && char != '\t')

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
