package com.virjar.tk.desktop

import java.net.URI

/**
 * Desktop 消息外链策略。与 Android 保持同一边界：仅允许可交给浏览器/邮件客户端的
 * http、https、mailto，拒绝本地资源、自定义协议、无 host 与携带账号信息的 URL。
 */
internal fun safeDesktopExternalLinkOrNull(rawUrl: String): String? {
    val candidate = rawUrl.trim()
    if (candidate.isEmpty()) return null

    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> candidate.takeIf {
            uri.host?.isNotBlank() == true && uri.userInfo == null
        }
        "mailto" -> candidate.takeIf {
            uri.rawSchemeSpecificPart?.isNotBlank() == true && !uri.rawSchemeSpecificPart.startsWith("//")
        }
        else -> null
    }
}
