package com.virjar.tk.app.media

/**
 * 由文件名扩展名推导附件 Content-Type 的纯规则。三端共用同一张表，
 * 避免各平台映射互有出入（如 .heic、.m4v 曾只在 iOS 可识别）。
 *
 * 平台若持有更权威的来源（Android ContentResolver / MimeTypeMap），
 * 应优先使用平台结果，本表作为统一兜底。
 */
fun attachmentContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "txt", "log" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "json" -> "application/json"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heic", "heif" -> "image/heic"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "amr" -> "audio/amr"
    else -> "application/octet-stream"
}
