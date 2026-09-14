package com.virjar.tk.app.ui.platform

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

internal actual suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText("TeamTalk", text)))
}

internal actual suspend fun Clipboard.readPlainText(): String? {
    val clip = getClipEntry()?.clipData ?: return null
    // 只读已存在的文本，不解析 URI 或加载剪贴板附件。
    return if (clip.itemCount == 1) clip.getItemAt(0).text?.toString() else null
}
