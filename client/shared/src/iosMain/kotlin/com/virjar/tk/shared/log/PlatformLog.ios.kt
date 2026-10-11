package com.virjar.tk.shared.log

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.create

@OptIn(BetaInteropApi::class)
internal actual fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?) {
    val message = "[$level][$tag] $msg${throwable?.let { "\n${it.stackTraceToString()}" }.orEmpty()}"
    // C 可变参数会把 Kotlin String 变成 char*，而 %@ 需要 Objective-C 对象。
    NSLog("%@", NSString.create(string = message))
}
