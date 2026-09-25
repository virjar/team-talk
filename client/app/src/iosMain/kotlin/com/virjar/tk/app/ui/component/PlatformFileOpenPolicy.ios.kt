package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.model.Attachment

/**
 * iOS 的 QuickLook 无法呈现安装包/压缩包/可执行文件：这类附件打开必然失败，
 * 一律走分享面板让用户存入"文件"应用，其余类型保持系统预览。
 */
actual fun platformFileOpenBehavior(attachment: Attachment): FileOpenBehavior =
    defaultFileOpenBehavior(attachment)
