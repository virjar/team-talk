package com.virjar.tk.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/** 返回一个与生命周期绑定的纯文本剪贴板写入器。 */
@Composable
internal fun rememberClipboardTextWriter(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text -> scope.launch { clipboard.setPlainText(text) } }
    }
}

internal expect suspend fun Clipboard.setPlainText(text: String)
