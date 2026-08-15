package com.virjar.tk.util

internal actual fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?) {
    when (level) {
        "trace" -> android.util.Log.i(tag, msg)
        "fault" -> {
            if (throwable != null) android.util.Log.e(tag, msg, throwable)
            else android.util.Log.e(tag, msg)
        }
        "snapshot" -> android.util.Log.i(tag, "[snapshot] $msg")
    }
}
