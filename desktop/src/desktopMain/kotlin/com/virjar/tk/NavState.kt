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
 * 三类页面容器（doc/04-ui-design/components.md §2.1）：
 * - 聊天检查器：群详情/成员/邀请（覆盖在聊天右侧，不替换聊天上下文）；
 * - 主内容页：全局搜索（替换右栏主内容）；
 * - 独立任务窗口：其余需要连续输入或选择的流程（宽 460、ESC 逐级返回）。
 *
 * 用户资料是轻量对象预览，不属于页面导航，单独由 [DesktopNav.profileUid] 驱动模态弹窗。
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

    // ── 聊天右侧检查器 ──
    data class GroupDetail(val chatId: String) : SubScreen()
    data class InviteMembers(val chatId: String) : SubScreen()
    data class InviteLinks(val chatId: String) : SubScreen()

    // ── 主内容页 ──
    data object GlobalSearch : SubScreen()

    /** Desktop 承载方式。显式建模，避免把“覆盖式检查器”和“主内容替换页”混成同一面板。 */
    val presentation: SubScreenPresentation
        get() = when (this) {
            is GroupDetail, is InviteMembers, is InviteLinks -> SubScreenPresentation.CHAT_INSPECTOR
            GlobalSearch -> SubScreenPresentation.MAIN_PANE
            else -> SubScreenPresentation.TASK_WINDOW
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
        is InviteLinks -> ScreenDataKey.InviteLinks(chatId)
        GlobalSearch -> null
        else -> null
    }
}

enum class SubScreenPresentation {
    TASK_WINDOW,
    MAIN_PANE,
    CHAT_INSPECTOR,
}

/**
 * Desktop 导航状态（数据层继承 [AppDataState]，导航字段仅 Desktop 使用）。
 *
 * 相对旧 AppState 的变化：
 * - 删除死字段 isWindowVisible/isWindowFocused（全仓零引用）；
 * - 子屏幕参数内联进 [SubScreen]，删除 3 个平行参数字段；
 * - 聊天检查器有导航栈（[inspectorStack]）：群详情→邀请成员 可逐级返回，
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

    /** 替换右栏主内容的页面；当前只有全局搜索。 */
    var mainPaneScreen by mutableStateOf<SubScreen?>(null)
        private set

    /** 聊天右侧覆盖式检查器导航栈（空 = 关闭）。入口重置为单元素，容器内跳转 push。 */
    var inspectorStack by mutableStateOf(emptyList<SubScreen>())
        private set

    /** Desktop 用户资料采用紧凑模态弹窗；null 表示未打开。 */
    var profileUid by mutableStateOf<String?>(null)

    /** 应用级搜索状态由顶栏和结果面板共享；不隶属于会话或通讯录 tab。 */
    var globalSearchQuery by mutableStateOf("")
    var searchFocusNonce by mutableIntStateOf(0)

    fun openGlobalSearch(requestFocus: Boolean = false) {
        profileUid = null
        windowScreen = null
        inspectorStack = emptyList()
        mainPaneScreen = SubScreen.GlobalSearch
        if (requestFocus) searchFocusNonce++
    }

    fun openProfile(uid: String) {
        if (uid.isNotBlank()) profileUid = uid
    }

    fun closeProfile() {
        profileUid = null
    }

    /** 按屏幕语义选择承载容器（§2.1）：任务窗口 / 主内容页 / 聊天检查器。 */
    fun openScreen(screen: SubScreen) {
        profileUid = null
        when (screen.presentation) {
            SubScreenPresentation.TASK_WINDOW -> {
                mainPaneScreen = null
                inspectorStack = emptyList()
                windowScreen = screen
            }
            SubScreenPresentation.MAIN_PANE -> {
                windowScreen = null
                inspectorStack = emptyList()
                mainPaneScreen = screen
            }
            SubScreenPresentation.CHAT_INSPECTOR -> {
                windowScreen = null
                mainPaneScreen = null
                inspectorStack = listOf(screen)
            }
        }
    }

    /** 打开聊天：清空面板与子窗口入口，右栏切聊天面板。 */
    fun openChat(chatId: String, chatName: String, chatType: Int = 1) {
        this.chatId = chatId
        this.chatName = chatName
        this.chatType = chatType
        globalSearchQuery = ""
        profileUid = null
        mainPaneScreen = null
        inspectorStack = emptyList()
        windowScreen = null
        prepareChat(chatId, chatName, chatType)
    }

    /** 检查器返回：栈>1 弹一级，否则关闭抽屉（ESC / 返回键 / 关闭按钮共用）。 */
    fun pushInspector(screen: SubScreen) {
        require(screen.presentation == SubScreenPresentation.CHAT_INSPECTOR) {
            "Only chat inspector screens can be pushed: $screen"
        }
        inspectorStack = inspectorStack + screen
    }

    fun popInspector() {
        inspectorStack = if (inspectorStack.size > 1) inspectorStack.dropLast(1) else emptyList()
    }

    fun closeInspector() {
        inspectorStack = emptyList()
    }

    fun closeMainPane() {
        if (mainPaneScreen is SubScreen.GlobalSearch) globalSearchQuery = ""
        mainPaneScreen = null
    }
}

@Composable
internal fun rememberDesktopNav(session: ClientSession): DesktopNav = remember { DesktopNav(session) }
