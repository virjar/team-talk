package com.virjar.tk.ios

import androidx.compose.runtime.*
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.ui.platform.IosBackDispatcher
import com.virjar.tk.app.viewmodel.ChatHistoryCategory
import com.virjar.tk.protocol.body.OfficeRefBody

internal enum class IosPage {
    HOME, CHAT, CHAT_TOOLS, CHAT_HISTORY_SEARCH, CHAT_HISTORY_BROWSER, SEARCH, SEARCH_USERS, CREATE_GROUP, FRIEND_APPLIES, USER_PROFILE, EDIT_PROFILE,
    CHANGE_PASSWORD, DEVICES, BLACKLIST, GROUP_DETAIL, GROUP_FILES, GROUP_BOTS,
    INVITE_MEMBERS, INVITE_LINKS, JOIN_BY_INVITE, FORWARD, LOCAL_STORAGE,
}
internal data class IosRoute(val page: IosPage, val id: String = "", val sequence: Long = 0L)

/** 分类浏览页的复杂入参；与 requestedDocument 同模式经导航器传递，不塞进路由字段。 */
internal data class ChatHistoryBrowserRequest(
    val chatId: String,
    val chatName: String,
    val category: ChatHistoryCategory,
    val initialKeyword: String = "",
)

/** Route identities remain Kotlin values; user IDs are never interpolated into URL paths. */
internal class IosNavigator {
    private val stack = mutableStateListOf(IosRoute(IosPage.HOME))
    val current: IosRoute get() = stack.last()
    val backDispatcher = IosBackDispatcher()
    var homeTab by mutableStateOf(MainTab.CONVERSATIONS)
    var requestedDocument by mutableStateOf<OfficeRefBody?>(null)
    var requestedTask by mutableStateOf<String?>(null)
    var requestedChatHistoryBrowser by mutableStateOf<ChatHistoryBrowserRequest?>(null)
    fun open(route: IosRoute) { if (stack.last() != route) stack += route }
    fun chat(chatId: String, sequence: Long = 0L) = open(IosRoute(IosPage.CHAT, chatId, sequence))
    fun profile(uid: String) = open(IosRoute(IosPage.USER_PROFILE, uid))
    fun home(tab: MainTab = homeTab) { stack.clear(); stack += IosRoute(IosPage.HOME); homeTab = tab }
    fun back() { if (!backDispatcher.onBack() && stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun document(reference: OfficeRefBody) { requestedDocument = reference; home(MainTab.DOCUMENTS) }
    fun task(taskId: String) { requestedTask = taskId; home(MainTab.TASKS) }
    fun chatHistoryBrowser(request: ChatHistoryBrowserRequest) {
        requestedChatHistoryBrowser = request
        open(IosRoute(IosPage.CHAT_HISTORY_BROWSER, request.chatId))
    }
}
