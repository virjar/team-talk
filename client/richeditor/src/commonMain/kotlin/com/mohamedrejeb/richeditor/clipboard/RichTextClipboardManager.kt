package com.mohamedrejeb.richeditor.clipboard

import androidx.compose.ui.platform.Clipboard
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Creates a new instance of [RichTextClipboardManager]
 * @param richTextState The [RichTextState] to be used for clipboard operations
 * @param clipboard The Compose [Clipboard] for handling clipboard operations
 * @param onClipboardFileUrlPaste [TT] 剪贴板文本恰好是本地已存在文件的 `file://` 引用时回调
 *   （微信/QQ 等 IM 复制图片只放文件引用而非位图/HTML）。宿主返回 true 表示已按文件/图片
 *   消费，本次粘贴不再向编辑器插入 URL 文本；返回 false 或为 null 时保持上游行为。
 * @return A new instance of [RichTextClipboardManager]
 */
internal expect fun createRichTextClipboardManager(
    richTextState: RichTextState,
    clipboard: Clipboard,
    onClipboardFileUrlPaste: ((String) -> Boolean)? = null,
): RichTextClipboardManager

/**
 * Platform-specific clipboard manager that intercepts [Clipboard] operations
 * to support rich text (HTML) copy/paste.
 *
 * Each platform implementation reads HTML from the native clipboard in [getClipEntry]
 * and writes HTML + plain text in [setClipEntry].
 */
internal interface RichTextClipboardManager : Clipboard
