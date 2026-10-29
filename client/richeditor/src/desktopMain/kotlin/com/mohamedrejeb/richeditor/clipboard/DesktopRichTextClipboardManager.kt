package com.mohamedrejeb.richeditor.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.awtClipboard
import com.mohamedrejeb.richeditor.model.RichTextState
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createRichTextClipboardManager(
    richTextState: RichTextState,
    clipboard: Clipboard,
    onClipboardFileUrlPaste: ((String) -> Boolean)?,
): RichTextClipboardManager =
    DesktopRichTextClipboardManager(
        richTextState = richTextState,
        clipboard = clipboard,
        onClipboardFileUrlPaste = onClipboardFileUrlPaste,
    )

/**
 * Desktop implementation of [RichTextClipboardManager].
 * Handles rich text clipboard operations using AWT's clipboard functionality.
 *
 * On paste, [getClipEntry] extracts HTML from the AWT clipboard and stores it in
 * [RichTextState.pendingClipboardHtml]. The actual HTML insertion happens in
 * [RichTextState.onTextFieldValueChange] when text is added.
 *
 * @property richTextState The [RichTextState] to be used for clipboard operations
 * @property clipboard The Compose [Clipboard] for handling clipboard operations
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class DesktopRichTextClipboardManager(
    private val richTextState: RichTextState,
    private val clipboard: Clipboard,
    private val onClipboardFileUrlPaste: ((String) -> Boolean)? = null,
) : RichTextClipboardManager, Clipboard by clipboard {

    override suspend fun getClipEntry(): ClipEntry? {
        var consumedFileUrl = false
        try {
            val transferable = awtClipboard?.getContents(null)
            if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.fragmentHtmlFlavor)) {
                val rawHtmlText =
                    withContext(Dispatchers.IO) {
                        transferable.getTransferData(DataFlavor.fragmentHtmlFlavor)
                    } as String
                richTextState.pendingClipboardHtml = rawHtmlText
            } else if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                // 纯文本裸 URL 粘贴自动成链（内测反馈 T048）
                val text =
                    withContext(Dispatchers.IO) {
                        transferable.getTransferData(DataFlavor.stringFlavor)
                    } as? String
                // [TT] 文本恰好是本地已存在文件的 file:// 引用时交给宿主导入（微信复制图片
                // 只放文件引用 + URL 格式，AWT 把 URL 当纯文本；宿主消费后本次粘贴为空）。
                val trimmed = text?.trim()
                if (trimmed != null && isLocalFileUrl(trimmed) &&
                    onClipboardFileUrlPaste?.invoke(trimmed) == true
                ) {
                    consumedFileUrl = true
                } else {
                    synthesizedLinkHtmlIfBareUrl(text)?.let { richTextState.pendingClipboardHtml = it }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (consumedFileUrl) return null
        return clipboard.getClipEntry()
    }

    private fun isLocalFileUrl(text: String): Boolean = try {
        val uri = java.net.URI(text)
        uri.scheme.equals("file", ignoreCase = true) && java.io.File(uri).isFile
    } catch (_: Exception) {
        false
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry == null) {
            clipboard.setClipEntry(null)
            return
        }

        val copySelection = richTextState.copySelection

        if (copySelection == null || copySelection.collapsed) {
            clipboard.setClipEntry(null)
            return
        }

        val html = richTextState.toHtml(copySelection)
        val text = richTextState.toText(copySelection)

        val htmlSelection = object : StringSelection(html), Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> =
                arrayOf(DataFlavor.fragmentHtmlFlavor, DataFlavor.stringFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
                flavor in getTransferDataFlavors()

            override fun getTransferData(flavor: DataFlavor?): Any = when (flavor) {
                DataFlavor.fragmentHtmlFlavor -> html
                DataFlavor.stringFlavor -> text
                else -> throw UnsupportedOperationException("Unsupported flavor: $flavor")
            }
        }

        awtClipboard?.setContents(htmlSelection, null)
    }
}
