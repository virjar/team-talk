package com.virjar.tk.app.ui.platform

/**
 * 格式化界面中显示的文件大小：字节取整，其余单位最多一位小数。
 * 同一附件在文件卡、群文件列表与更新对话框等所有入口显示一致。
 */
fun formatUiFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    if (unitIndex == 0) return "$bytes B"
    val rounded = ((size * 10).toLong().toDouble()) / 10.0
    val text = if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}" else rounded.toString()
    return "$text ${units[unitIndex]}"
}
