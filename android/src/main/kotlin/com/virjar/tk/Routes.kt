package com.virjar.tk

import com.virjar.tk.model.Attachment
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Android Navigation Compose 路由定义。
 *
 * 每条路由对应 NavHost 的一个 composable{} 目标。
 * 带参数的路由用函数式构造器（URL encode 安全）。
 */
object Routes {
    const val HOME = "home" // Tab 容器（底部导航常驻）

    const val CHAT = "chat/{chatId}?name={name}&type={type}"
    fun chat(chatId: String, name: String, type: Int = 1) =
        "chat/$chatId?name=${encodeChatRouteName(name)}&type=$type"
    const val SEARCH_MESSAGES = "search_messages"
    const val SEARCH_USERS = "search_users"
    const val CREATE_GROUP = "create_group?seedUid={seedUid}"
    fun createGroup(seedUid: String? = null) = if (seedUid.isNullOrBlank()) {
        "create_group"
    } else {
        "create_group?seedUid=${URLEncoder.encode(seedUid, "UTF-8")}"
    }
    const val FRIEND_APPLIES = "friend_applies"
    const val USER_PROFILE = "user_profile/{uid}"
    fun userProfile(uid: String) = "user_profile/$uid"
    const val EDIT_PROFILE = "edit_profile"
    const val CHANGE_PASSWORD = "change_password"
    const val DEVICES = "devices"
    const val BLACKLIST = "blacklist"
    const val GROUP_DETAIL = "group_detail/{chatId}"
    fun groupDetail(chatId: String) = "group_detail/$chatId"
    const val GROUP_FILES = "group_files/{chatId}"
    fun groupFiles(chatId: String) = "group_files/$chatId"
    const val INVITE_MEMBERS = "invite_members/{chatId}"
    fun inviteMembers(chatId: String) = "invite_members/$chatId"
    const val INVITE_LINKS = "invite_links/{chatId}"
    fun inviteLinks(chatId: String) = "invite_links/$chatId"
    const val FORWARD = "forward/{chatId}/{serverSeq}"
    fun forward(chatId: String, serverSeq: Long) = "forward/$chatId/$serverSeq"

    const val TEXT_ATTACHMENT_PREVIEW =
        "text_attachment_preview/{path}/{name}/{contentType}/{size}"
    fun textAttachmentPreview(attachment: Attachment) = buildString {
        append("text_attachment_preview/")
        append(encodeAttachmentRouteValue(attachment.path)).append('/')
        append(encodeAttachmentRouteValue(attachment.name)).append('/')
        append(encodeAttachmentRouteValue(attachment.contentType)).append('/')
        append(attachment.size.coerceAtLeast(0L))
    }
}

/** URL-safe Base64 前缀一个非空字符，使空 MIME/文件名也能成为稳定路由段。 */
internal fun encodeAttachmentRouteValue(value: String): String =
    "v" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

internal fun decodeAttachmentRouteValue(value: String): String {
    require(value.startsWith('v')) { "invalid attachment route value" }
    val payload = value.drop(1)
    if (payload.isEmpty()) return ""
    return String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
}

/**
 * Navigation Compose decodes URI percent escapes once while matching a query argument, but it
 * deliberately leaves '+' untouched. Encode the form value one extra time so the destination
 * receives one intact form-encoded layer and can perform the matching decode itself. This keeps
 * spaces, literal '+', '%', '&' and non-ASCII names distinguishable.
 */
internal fun encodeChatRouteName(name: String): String {
    val formEncoded = URLEncoder.encode(name, "UTF-8")
    return URLEncoder.encode(formEncoded, "UTF-8")
}

/** Decode exactly the form-encoded layer delivered to the CHAT destination. */
internal fun decodeChatRouteName(encodedName: String): String =
    URLDecoder.decode(escapeInvalidPercentEscapes(encodedName), "UTF-8")

/**
 * A restored route from the old single-encoding implementation may already contain a literal
 * percent after Navigation's decode pass. Preserve it rather than letting URLDecoder reject the
 * whole name (which would also leave its spaces as '+').
 */
private fun escapeInvalidPercentEscapes(value: String): String = buildString(value.length) {
    value.forEachIndexed { index, char ->
        if (char == '%' && (value.getOrNull(index + 1)?.isHexDigit() != true ||
                value.getOrNull(index + 2)?.isHexDigit() != true)) {
            append("%25")
        } else {
            append(char)
        }
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * 消息 Markdown 中的 mention uid 属于不可信输入，不能直接拼入 Navigation 路径。
 *
 * 服务端 UID 当前为 8 位 Base62，兼容历史 UUID；旧测试数据还会使用 `-`、`_`。
 * 这里按服务端字段上限（36）做 fail-closed 白名单，拒绝路径、查询、片段、转义与控制字符。
 */
internal fun safeMentionProfileRouteOrNull(rawUid: String): String? {
    if (rawUid.length !in 1..36) return null
    if (!rawUid.all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }) return null
    return Routes.userProfile(rawUid)
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
