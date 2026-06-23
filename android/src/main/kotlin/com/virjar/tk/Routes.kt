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
    const val CREATE_GROUP = "create_group"
    const val FRIEND_APPLIES = "friend_applies"
    const val USER_PROFILE = "user_profile/{uid}"
    fun userProfile(uid: String) = "user_profile/$uid"
    const val EDIT_PROFILE = "edit_profile"
    const val CHANGE_PASSWORD = "change_password"
    const val DEVICES = "devices"
    const val BLACKLIST = "blacklist"
    const val GROUP_DETAIL = "group_detail/{chatId}"
    fun groupDetail(chatId: String) = "group_detail/$chatId"
    const val INVITE_MEMBERS = "invite_members/{chatId}"
    fun inviteMembers(chatId: String) = "invite_members/$chatId"
    const val INVITE_LINKS = "invite_links/{chatId}"
    fun inviteLinks(chatId: String) = "invite_links/$chatId"
    const val FORWARD = "forward/{chatId}/{serverSeq}"
    fun forward(chatId: String, serverSeq: Long) = "forward/$chatId/$serverSeq"
}
