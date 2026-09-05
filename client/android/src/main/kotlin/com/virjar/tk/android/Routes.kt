package com.virjar.tk.android

import com.virjar.tk.protocol.model.Attachment
import java.net.URLEncoder
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

    const val CHAT = "chat/{chatId}?targetSeq={targetSeq}"
    fun chat(chatId: String, targetSeq: Long? = null): String {
        require(targetSeq == null || targetSeq > 0L) { "targetSeq must be positive" }
        return if (targetSeq == null) "chat/$chatId" else "chat/$chatId?targetSeq=$targetSeq"
    }
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
    const val GROUP_BOTS = "group_bots/{chatId}"
    fun groupBots(chatId: String) = "group_bots/$chatId"
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
 * 消息 Markdown 中的 mention uid 属于不可信输入，不能直接拼入 Navigation 路径。
 *
 * 人类账号只有 8 位 Base62 UID，服务账号只有服务端生成的小写标准 UUID。
 * 仅接受这两种当前模型，避免把任意历史字符串带入路由。
 */
internal fun safeMentionProfileRouteOrNull(rawUid: String): String? {
    val isHumanUid = rawUid.length == 8 && rawUid.all(Char::isAsciiLetterOrDigit)
    val isServiceUid = rawUid.length == 36 && rawUid.indices.all { index ->
        when (index) {
            8, 13, 18, 23 -> rawUid[index] == '-'
            else -> rawUid[index] in '0'..'9' || rawUid[index] in 'a'..'f'
        }
    }
    if (!isHumanUid && !isServiceUid) return null
    return Routes.userProfile(rawUid)
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
