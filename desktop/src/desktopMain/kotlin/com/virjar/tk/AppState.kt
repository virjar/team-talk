package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.*
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.navigation.ScreenDataKey
import com.virjar.tk.viewmodel.ChatViewModel
import com.virjar.tk.viewmodel.ContactViewModel
import com.virjar.tk.viewmodel.ConversationViewModel

/**
 * 共享状态持有者。封装所有平台通用的导航状态、子页面数据和 ViewModel。
 */

// ── 类型安全导航目标 ──

sealed class SubScreen {
    // 平台级屏幕
    object Main : SubScreen()
    object Chat : SubScreen()
    object Contacts : SubScreen()
    object Settings : SubScreen()

    // 共享子屏幕
    object Devices : SubScreen()
    object Blacklist : SubScreen()
    object EditProfile : SubScreen()
    object ChangePassword : SubScreen()
    object FriendApplies : SubScreen()
    object SearchUsers : SubScreen()
    object CreateGroup : SubScreen()
    object GroupDetail : SubScreen()
    object InviteMembers : SubScreen()
    object InviteLinks : SubScreen()
    object UserProfile : SubScreen()
    object Forward : SubScreen()
    object SearchMessages : SubScreen()
}

/**
 * Desktop 端导航状态持有者（继承 [AppDataState] 的数据层 + 保留导航字段）。
 *
 * Android 已迁移到 Navigation Compose（用 [AppDataState] + NavController），
 * 不再使用本类。本类保留供 Desktop 继续使用其手搓导航（currentScreen 枚举），
 * 待 Desktop 也独立重构导航后可移除。
 */
class AppState(session: ClientSession) : AppDataState(session) {

    // Window / focus（仅 Desktop 使用）
    var isWindowVisible by mutableStateOf(false)
    var isWindowFocused by mutableStateOf(false)

    // Navigation（仅 Desktop 使用）
    var currentScreen by mutableStateOf<SubScreen?>(null)
    var selectedTab by mutableIntStateOf(0)
    var selectedChatId by mutableStateOf<String?>(null)
    var selectedChatName by mutableStateOf("")
    var selectedChatType by mutableIntStateOf(1)

    // Sub-page data（导航专用，跨页面传递）
    var selectedGroupChatId by mutableStateOf<String?>(null)
    var selectedProfileUid by mutableStateOf<String?>(null)
    var forwardMessage by mutableStateOf<Message?>(null)

    /** Desktop 专用：openChat 同时建 VM + 写导航字段。 */
    fun openChat(chatId: String, chatName: String, chatType: Int = 1) {
        selectedChatId = chatId
        selectedChatName = chatName
        selectedChatType = chatType
        // 清除面板/子页面状态，确保右栏切换到聊天面板
        // （从通讯录用户详情切到会话时，currentScreen 还是 UserProfile 会导致右栏不切换）
        currentScreen = null
        prepareChat(chatId, chatName, chatType)
    }

    /** 是否为共享子屏幕（非平台级屏幕）—— Desktop 导航用 */
    val isSubScreen: Boolean get() = currentScreen != null && currentScreen !in PLATFORM_SCREENS

    /** Desktop 专用的按 SubScreen 加载数据（桥接到 AppDataState.loadScreenDataByKey）。 */
    suspend fun loadScreenData(screen: SubScreen?) {
        when (screen) {
            is SubScreen.Devices -> loadScreenDataByKey(ScreenDataKey.Devices)
            is SubScreen.Blacklist -> loadScreenDataByKey(ScreenDataKey.Blacklist)
            is SubScreen.FriendApplies -> loadScreenDataByKey(ScreenDataKey.FriendApplies)
            is SubScreen.GroupDetail -> selectedGroupChatId?.let { loadScreenDataByKey(ScreenDataKey.GroupDetail(it)) }
            is SubScreen.UserProfile -> selectedProfileUid?.let { loadScreenDataByKey(ScreenDataKey.UserProfile(it)) }
            is SubScreen.InviteLinks -> selectedGroupChatId?.let { loadScreenDataByKey(ScreenDataKey.InviteLinks(it)) }
            else -> {}
        }
    }

    companion object {
        private val PLATFORM_SCREENS = setOf(SubScreen.Main, SubScreen.Chat, SubScreen.Contacts, SubScreen.Settings)
    }
}

@Composable
fun rememberAppState(session: ClientSession): AppState = remember { AppState(session) }
