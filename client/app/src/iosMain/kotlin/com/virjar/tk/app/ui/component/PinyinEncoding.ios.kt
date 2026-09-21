package com.virjar.tk.app.ui.component

import kotlinx.cinterop.*
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun gbkDoubleByteCode(ch: Char): Int? {
    // GB18030 preserves the two-byte GBK/GB2312 code points used by the common partition table.
    val bytes = NSString.create(string = ch.toString()).dataUsingEncoding(
        CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.toUInt()),
        allowLossyConversion = false,
    ) ?: return null
    if (bytes.length != 2uL) return null
    val pointer = bytes.bytes?.reinterpret<UByteVar>() ?: return null
    return (pointer[0].toInt() shl 8) or pointer[1].toInt()
}
