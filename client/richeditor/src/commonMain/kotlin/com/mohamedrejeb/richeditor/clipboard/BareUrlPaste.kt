package com.mohamedrejeb.richeditor.clipboard

/**
 * 纯文本裸 URL 粘贴自动成链（内测反馈 T048）。剪贴板没有 HTML flavor、而纯文本是单个
 * http(s) URL 时，各平台 [RichTextClipboardManager] 用这里合成的 `<a>` HTML 走既有
 * pendingClipboardHtml 粘贴管线，编辑器内直接渲染为超链接，不再需要"插入链接"弹窗。
 * 显示文案需要定制的用户走 Markdown 源码模式。
 */
internal fun synthesizedLinkHtmlIfBareUrl(text: String?): String? {
    val value = text?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    if (value.any { it.isWhitespace() || it in "\\<>" }) return null
    val lower = value.lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
    if (value.substringAfter("://").isBlank()) return null
    return "<a href=\"$value\">$value</a>"
}
