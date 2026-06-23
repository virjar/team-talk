package com.virjar.tk.client

import android.content.Context
import java.io.File

/**
 * Android Context 持有者。由 Application.onCreate 初始化。
 */
object AndroidContext {
    lateinit var appContext: Context
}

actual fun platformDataDir(): File {
    return AndroidContext.appContext.getDir("teamtalk", Context.MODE_PRIVATE)
}
