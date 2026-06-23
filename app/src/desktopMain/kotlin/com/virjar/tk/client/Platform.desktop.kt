package com.virjar.tk.client

import java.io.File

actual fun platformDataDir(): File {
    // 优先使用 Main.kt 通过 -Dteamtalk.data.dir 设置的系统属性
    val prop = System.getProperty("teamtalk.data.dir")
    if (prop != null) return File(prop)
    return File(System.getProperty("user.home"), ".teamtalk")
}
