package com.virjar.tk.app.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.DataFlavor

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(StringSelection(text)))
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun Clipboard.readPlainText(): String? {
    val content = getClipEntry()?.asAwtTransferable ?: return null
    return if (content.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        content.getTransferData(DataFlavor.stringFlavor) as? String
    } else null
}
