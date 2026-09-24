@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.shared.platform.PlatformFile
import com.virjar.tk.shared.platform.absolutePath
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIImage
import platform.UIKit.UIPasteboard

/**
 * 复制图片消息：把本地图片文件写进系统剪贴板。优先按真实格式放数据
 * （粘贴方按 UTI 识别）；无法识别的类型退回 UIImage，由 UIKit 选择表示。
 */
internal fun copyImageFileToPasteboard(file: PlatformFile, contentType: String): Boolean {
    val data = NSData.dataWithContentsOfFile(file.absolutePath) ?: return false
    val pasteboard = UIPasteboard.generalPasteboard
    val utType = imagePasteboardType(contentType)
    return if (utType != null) {
        pasteboard.setData(data, forPasteboardType = utType)
        true
    } else {
        val image = UIImage.imageWithData(data) ?: return false
        pasteboard.image = image
        true
    }
}

private fun imagePasteboardType(contentType: String): String? = when (contentType.substringBefore(';')) {
    "image/png" -> "public.png"
    "image/jpeg", "image/jpg" -> "public.jpeg"
    "image/gif" -> "public.gif"
    "image/webp" -> "org.webpproject.webp"
    else -> null
}
