package com.virjar.tk.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.ScreenDataKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftStore
import com.virjar.tk.app.viewmodel.MessageFocusTarget

/**
 * 子屏幕目标（Desktop）。参数内联在目标里（对齐 Android Routes(chatId=…) 模式），
 * 取代旧的空枚举 + selectedGroupChatId/selectedProfileUid/forwardMessage 平行字段。
 *
 * 三类页面容器（doc/05-clients/desktop.md）：
 * - 聊天检查器：群详情/成员/邀请（覆盖在聊天右侧，不替换聊天上下文）；
 * - 主内容页：全局搜索（替换右栏主内容）；
 * - 独立任务窗口：其余需要连续输入或选择的流程（宽 460、ESC 逐级返回）。
 *
 * 用户资料与个人设置是主窗口模态弹窗，不进入页面导航：前者由 [DesktopNav.profileUid] 驱动，
 * 后者由 [DesktopNav.settingsOpen] 驱动（编辑资料、修改密码、设备管理、黑名单是其内部子视图）。
 */
sealed class SubScreen {
    // ── 独立子窗口类 ──
    data object FriendApplies : SubScreen()
    data object SearchUsers : SubScreen()
    data class CreateGroup(val preselectedUids: Set<String> = emptySet()) : SubScreen()
    data object SearchMessages : SubScreen()
    data class Forward(val message: Message) : SubScreen()

    // ── 聊天右侧检查器 ──
    data class GroupDetail(val chatId: String) : SubScreen()
    data class InviteMembers(val chatId: String) : SubScreen()
    data class InviteLinks(val chatId: String) : SubScreen()
    data class GroupFiles(val chatId: String) : SubScreen()
    data class GroupBots(val chatId: String) : SubScreen()

    // ── 主内容页 ──
    data object GlobalSearch : SubScreen()

    /** Desktop 承载方式。显式建模，避免把“覆盖式检查器”和“主内容替换页”混成同一面板。 */
    val presentation: SubScreenPresentation
        get() = when (this) {
            is GroupDetail, is InviteMembers, is InviteLinks, is GroupFiles, is GroupBots ->
                SubScreenPresentation.CHAT_INSPECTOR
            GlobalSearch -> SubScreenPresentation.MAIN_PANE
            else -> SubScreenPresentation.TASK_WINDOW
        }

    /** 子窗口高度（宽统一 460，§2.6；高按内容）。 */
    val windowHeight: Dp
        get() = when (this) {
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
        FriendApplies -> ScreenDataKey.FriendApplies
        is GroupDetail -> ScreenDataKey.GroupDetail(chatId)
        is InviteLinks -> ScreenDataKey.InviteLinks(chatId)
        is GroupFiles -> ScreenDataKey.GroupFiles(chatId)
        is GroupBots -> ScreenDataKey.GroupBots(chatId)
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
class DesktopNav(
    session: ClientSession,
    documentDrafts: DocumentDraftStore,
    onAuthExpired: () -> Unit,
    onHttpAuthExpired: (rejectedAccessToken: String) -> Unit,
) : AppDataState(
    session = session,
    documentDrafts = documentDrafts,
    onAuthExpired = onAuthExpired,
    onHttpAuthExpired = onHttpAuthExpired,
) {

    var selectedTab by mutableIntStateOf(0)

    /**
     * 文档工作台可从主窗口拉出。拉出期间主窗口只显示承接态，不再挂载第二个工作台，
     * 因而独立窗口独占导航位置；[documents] 中的标签与草稿仍可在收回后连续使用。
     */
    var documentWindowVisible by mutableStateOf(false)

    // 当前聊天只保留稳定身份；名称、类型始终来自 LocalCache 会话投影。
    var chatId by mutableStateOf<String?>(null)

    /** 搜索导航会保留精确的消息身份，直到共享 ChatPanel 消费它。 */
    var messageFocusTarget by mutableStateOf<MessageFocusTarget?>(null)
        private set

    /** 每次搜索结果点击都是一次意图，包括对同一结果的重复点击。 */
    var messageFocusRequestId by mutableLongStateOf(0L)
        private set

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

    /** 个人设置居中模态；打开时工作区保持当前一级栏目不变。 */
    var settingsOpen by mutableStateOf(false)

    fun openSettings() {
        profileUid = null
        settingsOpen = true
    }

    fun closeSettings() {
        settingsOpen = false
    }

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
    fun openChat(chatId: String) {
        openChat(chatId, messageFocusTarget = null)
    }

    fun openMessage(chatId: String, serverSeq: Long) {
        messageFocusRequestId = nextDesktopMessageFocusRequestId(messageFocusRequestId)
        openChat(chatId, desktopMessageFocusTarget(chatId, serverSeq))
    }

    private fun openChat(chatId: String, messageFocusTarget: MessageFocusTarget?) {
        if (!prepareChat(chatId)) return
        this.chatId = chatId
        this.messageFocusTarget = messageFocusTarget
        globalSearchQuery = ""
        profileUid = null
        settingsOpen = false
        mainPaneScreen = null
        inspectorStack = emptyList()
        windowScreen = null
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

internal fun desktopMessageFocusTarget(chatId: String, serverSeq: Long): MessageFocusTarget =
    MessageFocusTarget(chatId, serverSeq)

internal fun nextDesktopMessageFocusRequestId(current: Long): Long {
    check(current < Long.MAX_VALUE) { "desktop message focus request id exhausted" }
    return current + 1L
}
