package com.virjar.tk.app.ui.component

internal actual fun gbkDoubleByteCode(ch: Char): Int? {
    val bytes = ch.toString().toByteArray(charset("GBK"))
    return if (bytes.size == 2) ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255) else null
}
