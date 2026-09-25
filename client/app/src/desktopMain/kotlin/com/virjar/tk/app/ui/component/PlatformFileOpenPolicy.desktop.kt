package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.model.Attachment

/**
 * 桌面没有统一的安装器：apk 打不开，zip 解压应手动落位，exe/msi 在 Windows 上
 * 点击即执行有风险——安装包/压缩包/可执行文件一律只保存，不做主动打开。
 */
actual fun platformFileOpenBehavior(attachment: Attachment): FileOpenBehavior =
    defaultFileOpenBehavior(attachment)
