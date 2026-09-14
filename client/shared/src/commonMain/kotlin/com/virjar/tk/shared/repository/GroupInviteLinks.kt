package com.virjar.tk.shared.repository

import java.net.URI
import java.util.UUID

/** 可分享地址只携带邀请凭据；不会改变客户端连接的部署。 */
object GroupInviteLinks {
    fun create(serverBaseUrl: String, token: String): String =
        "${canonicalHttpServerBase(serverBaseUrl)}/invite#${canonicalToken(token)}"

    /** 从点击的链接或剪贴板分享文本中识别一个邀请；不联网，也不改变当前部署。 */
    fun find(input: String): String? {
        val text = input.trim().takeIf { it.isNotEmpty() && it.length <= 16_384 } ?: return null
        if (text.length == 36) {
            runCatching { canonicalToken(text) }.getOrNull()?.let { return it }
        }
        val links = pastedUrls.findAll(text).mapNotNull { match ->
            val candidate = match.value.trimEnd('。', '，', '；', '、', '.', ',', ';', '!', '！', ')', '）', ']', '】', '}')
            runCatching {
                val uri = URI(candidate)
                create(inviteBase(uri), canonicalToken(uri.rawFragment.orEmpty()))
            }.getOrNull()
        }.distinct().take(2).toList()
        // 多个不同邀请不猜测用户想加入哪个群；点击具体链接后再进入。
        return links.singleOrNull()
    }

    /** 兼容旧邀请码。完整链接必须属于当前部署，检查在任何 RPC 之前完成。 */
    fun parse(input: String, serverBaseUrl: String): String {
        val value = input.trim()
        require(value.isNotEmpty() && value.length <= 2048) { "请粘贴完整邀请链接或邀请码" }
        if (value.length == 36 && ':' !in value && '/' !in value) return canonicalToken(value)
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("邀请链接格式不正确")
        }
        val linkedBase = inviteBase(uri)
        require(canonicalHttpServerBase(linkedBase) == canonicalHttpServerBase(serverBaseUrl)) {
            "此邀请属于其他服务器，请使用对应服务器的客户端"
        }
        return canonicalToken(uri.rawFragment.orEmpty())
    }

    private fun inviteBase(uri: URI): String {
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawPath.orEmpty().endsWith("/invite")) {
            "请粘贴完整邀请链接或邀请码"
        }
        return URI(uri.scheme, null, uri.host, uri.port, uri.path.removeSuffix("/invite"), null, null).toASCIIString()
    }

    private val pastedUrls = Regex("https?://[^\\s<>\\\"“”]+", RegexOption.IGNORE_CASE)

    private fun canonicalToken(value: String): String {
        val token = value.takeIf { it.length == 36 }
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
        require(token != null && token == value.lowercase()) { "邀请码格式不正确，请复制完整链接或邀请码" }
        return token
    }
}
