package com.virjar.tk.app.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun Clipboard.setPlainText(text: String) {
    nativeClipboard.string = text
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun Clipboard.readPlainText(): String? = nativeClipboard.string
