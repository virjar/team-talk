package com.virjar.tk.util

/**
 * Cross-platform logging facade.
 * Desktop: SLF4J + Logback (console + file)
 * Android: android.util.Log (logcat)
 */
object AppLog {
    fun d(tag: String, msg: String) = platformLogDebug(tag, msg)
    fun i(tag: String, msg: String) = platformLogInfo(tag, msg)
    fun w(tag: String, msg: String) = platformLogWarn(tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable) = platformLogWarn(tag, msg, t)
    fun e(tag: String, msg: String) = platformLogError(tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable) = platformLogError(tag, msg, t)
}

internal expect fun platformLogDebug(tag: String, msg: String)
internal expect fun platformLogInfo(tag: String, msg: String)
internal expect fun platformLogWarn(tag: String, msg: String, t: Throwable?)
internal expect fun platformLogError(tag: String, msg: String, t: Throwable?)
