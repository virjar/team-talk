package com.virjar.tk.shared.log

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.create

@OptIn(BetaInteropApi::class)
internal actual fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?) {
    val message = "[$level][$tag] $msg${throwable?.let { "\n${it.stackTraceToString()}" }.orEmpty()}"
    // C varargs turn Kotlin String into char*, while %@ requires an Objective-C object.
    NSLog("%@", NSString.create(string = message))
}
