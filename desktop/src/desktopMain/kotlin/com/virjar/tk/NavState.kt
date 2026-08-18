package com.virjar.tk

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Message
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.navigation.ScreenDataKey

/**
 * 子屏幕目标（Desktop）。参数内联在目标里（对齐 Android Routes(chatId=…) 模式），
 * 取代旧的空枚举 + selectedGroupChatId/selectedProfileUid/forwardMessage 平行字段。
 *
 * 两类容器（doc/04-ui-design/components.md §2.1）：
 * - 右栏面板：群详情/成员/用户资料/邀请（与聊天上下文相关，原地替换聊天区，ESC 关）；
 * - 独立子窗口：其余全部（§2.6：统一头部返回键+标题、宽 460、ESC 逐级返回）。
 */
sealed class SubScreen {
    // ── 独立子窗口类 ──
    data object Devices : SubScreen()
    data object Blacklist : SubScreen()
    data object EditProfile : SubScreen()
    data object ChangePassword : SubScreen()
    data object FriendApplies : SubScreen()
    data object SearchUsers : SubScreen()
    data class CreateGroup(val preselectedUids: Set<String> = emptySet()) : SubScreen()
    data object SearchMessages : SubScreen()
    data class Forward(val message: Message) : SubScreen()

    // ── 右栏面板类 ──
    data class GroupDetail(val chatId: String) : SubScreen()
    data class InviteMembers(val chatId: String) : SubScreen()
    data class InviteLinks(val chatId: String) : SubScreen()
    data class UserProfile(val uid: String) : SubScreen()
    data object GlobalSearch : SubScreen()

    /** 是否渲染为右栏面板（其余为独立子窗口）。仅约束顶层入口；容器内局部跳转不受限。 */
    val isPanel: Boolean
        get() = this is GroupDetail || this is InviteMembers || this is InviteLinks ||
            this is UserProfile || this is GlobalSearch

    /** 子屏幕标题（子窗口标题栏/面板头部共用）。 */
    val title: String
        get() = when (this) {
            Devices -> "设备管理"
            Blacklist -> "黑名单"
            EditProfile -> "编辑资料"
            ChangePassword -> "修改密码"
            FriendApplies -> "好友申请"
            SearchUsers -> "搜索用户"
            is CreateGroup -> "创建群组"
            SearchMessages -> "搜索消息"
            is Forward -> "转发到"
            is GroupDetail -> "群详情"
            is InviteMembers -> "邀请成员"
            is InviteLinks -> "邀请链接"
            is UserProfile -> "用户资料"
            GlobalSearch -> "搜索"
        }

    /** 子窗口高度（宽统一 460，§2.6；高按内容）。 */
    val windowHeight: Dp
        get() = when (this) {
            EditProfile -> 360.dp
            ChangePassword -> 400.dp
            Devices -> 500.dp
            Blacklist -> 500.dp
            FriendApplies -> 500.dp
            SearchUsers -> 560.dp
            is CreateGroup -> 560.dp
            SearchMessages -> 560.dp
            is Forward -> 500.dp
            GlobalSearch -> 560.dp
            else -> 500.dp
        }

    /** 预加载的共享数据键（null = 无需预载）。 */
    fun dataKey(): ScreenDataKey? = when (this) {
        Devices -> ScreenDataKey.Devices
        Blacklist -> ScreenDataKey.Blacklist
        FriendApplies -> ScreenDataKey.FriendApplies
        is GroupDetail -> ScreenDataKey.GroupDetail(chatId)
        is UserProfile -> ScreenDataKey.UserProfile(uid)
        is InviteLinks -> ScreenDataKey.InviteLinks(chatId)
        GlobalSearch -> null
        else -> null
    }
}

/**
 * Desktop 导航状态（数据层继承 [AppDataState]，导航字段仅 Desktop 使用）。
 *
 * 相对旧 AppState 的变化：
 * - 删除死字段 isWindowVisible/isWindowFocused（全仓零引用）；
 * - 子屏幕参数内联进 [SubScreen]，删除 3 个平行参数字段；
 * - 面板容器有导航栈（[panelStack]）：群详情→邀请成员 可逐级返回，
 *   旧单槽 currentScreen 无栈，InviteMembers 只能硬编码跳回 GroupDetail。
 */
class DesktopNav(session: ClientSession) : AppDataState(session) {

    var selectedTab by mutableIntStateOf(0)

    // 当前聊天（右栏聊天面板；null = 空态）
    var chatId by mutableStateOf<String?>(null)
    var chatName by mutableStateOf("")
    var chatType by mutableIntStateOf(1)

    /** 独立子窗口入口（null = 无窗口）。窗口内局部导航栈由 SubWindow 自行维护。 */
    var windowScreen by mutableStateOf<SubScreen?>(null)

    /** 右栏面板导航栈（空 = 无面板）。入口重置为单元素，容器内跳转 push。 */
    var panelStack by mutableStateOf(emptyList<SubScreen>())

    /** 应用级搜索状态由顶栏和结果面板共享；不隶属于会话或通讯录 tab。 */
    var globalSearchQuery by mutableStateOf("")
    var searchFocusNonce by mutableIntStateOf(0)

    fun openGlobalSearch(requestFocus: Boolean = false) {
        windowScreen = null
        panelStack = listOf(SubScreen.GlobalSearch)
        if (requestFocus) searchFocusNonce++
    }

    /** 打开子屏幕：面板类进右栏面板栈，其余弹独立子窗口（§2.1 容器分流）。 */
    fun openScreen(screen: SubScreen) {
        if (screen.isPanel) {
            windowScreen = null
            panelStack = listOf(screen)
        } else {
            panelStack = emptyList()
            windowScreen = screen
        }
    }

    /** 打开聊天：清空面板与子窗口入口，右栏切聊天面板。 */
    fun openChat(chatId: String, chatName: String, chatType: Int = 1) {
        this.chatId = chatId
        this.chatName = chatName
        this.chatType = chatType
        globalSearchQuery = ""
        panelStack = emptyList()
        windowScreen = null
        prepareChat(chatId, chatName, chatType)
    }

    /** 面板返回：栈>1 弹一级，否则清空（右栏 ESC / 返回键共用）。 */
    fun popPanel() {
        if (panelStack.lastOrNull() is SubScreen.GlobalSearch) globalSearchQuery = ""
        panelStack = if (panelStack.size > 1) panelStack.dropLast(1) else emptyList()
    }
}

@Composable
internal fun rememberDesktopNav(session: ClientSession): DesktopNav = remember { DesktopNav(session) }
