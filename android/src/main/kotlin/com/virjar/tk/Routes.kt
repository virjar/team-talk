package com.virjar.tk

import java.net.URLEncoder

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
        "chat/$chatId?name=${URLEncoder.encode(name, "UTF-8")}&type=$type"
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
}

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
