package com.virjar.tk.app.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(StringSelection(text)))
}
