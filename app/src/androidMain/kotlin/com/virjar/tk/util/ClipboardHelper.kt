package com.virjar.tk.util

import android.content.ClipData
import android.content.Context

actual fun copyToClipboard(text: String) {
    val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
}

private lateinit var applicationContext: Context

fun initClipboardHelper(context: Context) {
    applicationContext = context.applicationContext
}
