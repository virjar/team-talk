package com.virjar.tk.shared.repository

import java.net.URI
import java.util.UUID

/** 可分享地址只携带邀请凭据；不会改变客户端连接的部署。 */
object GroupInviteLinks {
    fun create(serverBaseUrl: String, token: String): String =
        "${canonicalHttpServerBase(serverBaseUrl)}/invite#${canonicalToken(token)}"

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
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawPath.orEmpty().endsWith("/invite")) {
            "请粘贴完整邀请链接或邀请码"
        }
        val linkedBase = URI(
            uri.scheme, null, uri.host, uri.port, uri.path.removeSuffix("/invite"), null, null,
        ).toASCIIString()
        require(canonicalHttpServerBase(linkedBase) == canonicalHttpServerBase(serverBaseUrl)) {
            "此邀请属于其他服务器，请使用对应服务器的客户端"
        }
        return canonicalToken(uri.rawFragment.orEmpty())
    }

    private fun canonicalToken(value: String): String {
        val token = value.takeIf { it.length == 36 }
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
        require(token != null && token == value.lowercase()) { "邀请码格式不正确，请复制完整链接或邀请码" }
        return token
    }
}
