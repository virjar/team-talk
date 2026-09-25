package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.model.Attachment

/**
 * 文件类附件（文本预览之外）的点按行为抽象：同一个文件在不同平台有不同的处理能力，
 * "点击即交给系统打开"不是各端都成立的默认。
 *
 * - 安装包/可执行文件/压缩包（apk、exe、zip 等）：桌面点击打开要么失败、要么有执行风险，
 *   压缩包的归宿通常是手动落位后解压，因此这些类型各端默认只保存，不做主动打开；
 * - 例外由平台 actual 覆写：apk 在 Android 上"打开"就是拉起安装器，属于平台默认行为。
 * - 文本类文件优先进入内嵌预览（textAttachmentPreviewPlan），不经过本策略。
 */
enum class FileOpenBehavior {
    /** 交给系统处理：Android 拉起安装器或系统面板，桌面交给关联应用。 */
    OPEN_WITH_SYSTEM,

    /** 不主动打开：确认下载后直接走"另存为"，由用户决定落位。 */
    SAVE_ONLY,
}

/** 安装包、可执行文件与压缩包扩展名：任何平台都不适合"点了就交给系统打开"。 */
internal val INSTALLER_ARCHIVE_EXECUTABLE_EXTENSIONS = setOf(
    "apk", "ipa", "exe", "msi", "msix", "dmg", "pkg", "deb", "rpm",
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "jar",
)

/** 按扩展名的保守默认：安装包/压缩包/可执行文件只保存，其余交给系统。 */
fun defaultFileOpenBehavior(attachment: Attachment): FileOpenBehavior {
    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    return if (extension in INSTALLER_ARCHIVE_EXECUTABLE_EXTENSIONS) {
        FileOpenBehavior.SAVE_ONLY
    } else {
        FileOpenBehavior.OPEN_WITH_SYSTEM
    }
}

/** 各平台按自身处理能力覆写：Android 的 apk 走安装器（打开即安装），其余沿用保守默认。 */
expect fun platformFileOpenBehavior(attachment: Attachment): FileOpenBehavior
