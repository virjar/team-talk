package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.model.Attachment

/**
 * Android 有系统安装器：apk 的"打开"就是安装，属于平台默认行为；
 * 其余安装包/压缩包/可执行文件没有可靠的系统处理器，沿用保守默认只保存。
 */
actual fun platformFileOpenBehavior(attachment: Attachment): FileOpenBehavior {
    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    if (extension == "apk") return FileOpenBehavior.OPEN_WITH_SYSTEM
    return defaultFileOpenBehavior(attachment)
}
