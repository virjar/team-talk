package com.virjar.tk.util

import android.util.Log

internal actual fun platformLogDebug(tag: String, msg: String) {
    Log.d(tag, msg)
}

internal actual fun platformLogInfo(tag: String, msg: String) {
    Log.i(tag, msg)
}

internal actual fun platformLogWarn(tag: String, msg: String, t: Throwable?) {
    if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
}

internal actual fun platformLogError(tag: String, msg: String, t: Throwable?) {
    if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
}
