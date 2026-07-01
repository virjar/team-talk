package com.virjar.tk.navigation

import androidx.compose.runtime.*
import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.logUnhandledError
import com.virjar.tk.model.*
import com.virjar.tk.viewmodel.ChatViewModel
import com.virjar.tk.viewmodel.ContactViewModel
import com.virjar.tk.viewmodel.ConversationViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 纯数据/业务状态持有者（不含导航逻辑）。
 *
 * 从 [AppState] 拆出，供 Android（用 Navigation Compose 自行管理导航）和
 * Desktop（用 [AppState] 含导航字段）共享同一套数据层。
 *
 * 导航（页面流转、currentScreen 等）由各平台独立实现：
 * - Android：NavController + NavHost
 * - Desktop：[AppState] 的 currentScreen 枚举（暂保留）
 *
 * @see AppState
 */
open class AppDataState(val session: ClientSession) {
    val imClient get() = session.imClient
    val userSession get() = session.userSession
    val localCache get() = session.localCache
    val chatRepo get() = session.chatRepo
    val contactRepo get() = session.contactRepo
    val messageRepo get() = session.messageRepo
    val deviceRepo get() = session.deviceRepo
    val userRepo get() = session.userRepo
    val conversationRepo get() = session.conversationRepo

    /**
     * 子页面 action 专用协程作用域。与 ViewModels 的 scope 分离，
     * 便于统一管理 action 的错误兜底；[destroy] 时取消。
     */
    private val actionScope = CoroutineScope(Dispatchers.Main + SupervisorJob() +
        CoroutineExceptionHandler { _, t -> logUnhandledError("AppDataState", t) })

    // ViewModels
    val conversationViewModel = ConversationViewModel(localCache, conversationRepo)
    val contactViewModel = ContactViewModel(localCache, contactRepo, userSession.uid).also {
        // 收到好友申请/接受/删除通知时刷新红点数
        session.eventProcessor.onContactChanged = { it.refreshPendingApplyCount() }
    }
    var chatViewModel by mutableStateOf<ChatViewModel?>(null)

    // 屏幕数据（各二级页面加载后缓存）
    var devices by mutableStateOf(emptyList<Device>())
    var blockedContacts by mutableStateOf(emptyList<Contact>())
    var applies by mutableStateOf(emptyList<ContactApply>())
    var groupDetailChat by mutableStateOf<Chat?>(null)
    var groupMembers by mutableStateOf(emptyList<Member>())
    var profileUser by mutableStateOf<User?>(null)
    var isFriend by mutableStateOf(false)
    var inviteLinks by mutableStateOf(emptyList<InviteLink>())

    // Error state
    var error by mutableStateOf<String?>(null)
        internal set

    // Derived
    val currentUser: User? get() {
        val uid = userSession.uid
        if (uid.isBlank()) return null
        // 优先从 localCache 取（USER_UPDATED 事件会 upsert），
        // 注册/登录后缓存可能还没写入，回退用 UserSession 内存字段构建。
        return localCache.getUser(uid)
            ?: User(
                uid = uid,
                username = userSession.username ?: "",
                name = userSession.name ?: "",
            )
    }

    /** 释放 ViewModel 协程作用域。在登出或会话结束时调用。 */
    fun destroy() {
        conversationViewModel.destroy()
        contactViewModel.destroy()
        chatViewModel?.destroy()
        actionScope.cancel()
    }

    /**
     * 准备聊天 ViewModel（纯数据操作，不含导航）。
     * 导航由调用方（Android NavController / Desktop currentScreen）负责。
     */
    fun prepareChat(chatId: String, chatName: String, chatType: Int = ChatType.PERSONAL.code) {
        chatViewModel?.destroy()
        chatViewModel = ChatViewModel(chatId, localCache, messageRepo, session.eventProcessor, userSession.uid).apply {
            // 认证失效：ViewModel 不自断连接，统一上抛给会话所有者（owner-driven）
            onAuthExpired = { session.close() }
        }
    }

    fun clearError() { error = null }

    /**
     * 统一处理 Repository 抛出的错误。
     * 认证失效：停而非重试 → session.close()。
     */
    fun handleError(e: Throwable, fallbackMsg: String) {
        when (e) {
            is AppError.AuthExpired -> {
                error = "认证失效，请重新登录"
                session.close()
            }
            is AppError.FatalCodec -> {
                // 编解码错误是 FATAL 级别（客户端与服务端协议不一致），醒目提示用户上报
                error = "⚠️ 数据协议错误，请联系开发者：${e.message}"
            }
            is AppError -> error = e.message ?: fallbackMsg
            else -> error = fallbackMsg
        }
    }

    /**
     * 按屏幕类型加载对应数据。Android 端可在 NavHost 的各 composable{} 内用
     * LaunchedEffect 调用对应的加载逻辑（见 [loadScreenDataByKey]）。
     */
    suspend fun loadScreenDataByKey(key: ScreenDataKey) {
        when (key) {
            is ScreenDataKey.Devices -> try { devices = deviceRepo.listDevices().getOrThrow() } catch (e: AppError) { handleError(e, "加载设备列表失败") }
            is ScreenDataKey.Blacklist -> try { blockedContacts = contactRepo.listBlacklist().getOrThrow() } catch (e: AppError) { handleError(e, "加载黑名单失败") }
            is ScreenDataKey.FriendApplies -> try { applies = contactRepo.listApplies().getOrThrow() } catch (e: AppError) { handleError(e, "加载好友申请失败") }
            is ScreenDataKey.GroupDetail -> {
                val chatId = key.chatId
                try {
                    groupDetailChat = chatRepo.getChat(chatId).getOrThrow()
                    groupMembers = chatRepo.getMembers(chatId).getOrThrow()
                } catch (e: AppError) { handleError(e, "加载群详情失败") }
            }
            is ScreenDataKey.UserProfile -> {
                val uid = key.uid
                try { profileUser = userRepo.getProfile(uid).getOrThrow() } catch (e: AppError) { handleError(e, "加载用户信息失败") }
                isFriend = contactViewModel.contacts.value.any { it.friendUid == uid }
            }
            is ScreenDataKey.InviteLinks -> {
                val chatId = key.chatId
                try { inviteLinks = chatRepo.listInviteLinks(chatId).getOrThrow() } catch (e: AppError) { handleError(e, "加载邀请链接失败") }
            }
        }
    }

    // ── 子页面 action（统一封装 repo 调用 + 错误处理 + 数据刷新） ──
    // UI 层（Android NavHost / Desktop SubScreenRouter）只需调用这些方法 + 处理导航，
    // 不再各自重复 try { repo.x().getOrThrow() } catch (e: AppError) { handleError(...) }。
    //
    // 两类约定：
    // - 需要 UI 同步/suspend 拿结果的方法声明为 suspend，由 Screen 的 suspend 回调直接调用；
    // - 纯副作用（fire-and-forget，UI 不关心返回值）用 launch 启动。

    /** 设备管理：踢出设备，成功后刷新设备列表。 */
    fun kickDevice(deviceId: String) = actionScope.launch {
        try { deviceRepo.kickDevice(deviceId).getOrThrow(); devices = deviceRepo.listDevices().getOrThrow() }
        catch (e: AppError) { handleError(e, "踢出设备失败") }
    }

    /** 黑名单：移出黑名单，成功后刷新黑名单列表。 */
    fun unblockContact(uid: String) = actionScope.launch {
        try { contactRepo.removeFromBlacklist(uid).getOrThrow(); blockedContacts = contactRepo.listBlacklist().getOrThrow() }
        catch (e: AppError) { handleError(e, "移出黑名单失败") }
    }

    /** 编辑资料：保存昵称/手机号。返回是否成功（UI 据此决定是否返回）。 */
    suspend fun saveProfile(name: String, phone: String?): Boolean = try {
        userRepo.updateProfile(name = name, phone = phone).getOrThrow(); true
    } catch (e: AppError) { handleError(e, "保存失败"); false }

    /** 修改密码。返回是否成功。 */
    suspend fun changePassword(old: String, new: String): Boolean = try {
        userRepo.changePassword(old, new).getOrThrow(); true
    } catch (e: AppError) { handleError(e, "修改密码失败"); false }

    /** 好友申请：接受，成功后刷新申请列表。 */
    fun acceptFriendApply(token: String) = actionScope.launch {
        try { contactRepo.accept(token).getOrThrow(); applies = contactRepo.listApplies().getOrThrow() }
        catch (e: AppError) { handleError(e, "接受申请失败") }
    }

    /** 好友申请：拒绝，成功后刷新申请列表。 */
    fun rejectFriendApply(token: String) = actionScope.launch {
        try { contactRepo.reject(token).getOrThrow(); applies = contactRepo.listApplies().getOrThrow() }
        catch (e: AppError) { handleError(e, "拒绝申请失败") }
    }

    /** 创建群组。返回 chatId 或 null（UI 据此决定是否打开聊天）。 */
    suspend fun createGroup(name: String, memberUids: List<String>): String? = try {
        val chat = chatRepo.createGroup(name, memberUids = memberUids).getOrThrow()
        conversationRepo.listConversations()
        chat.chatId
    } catch (e: AppError) { handleError(e, "创建群组失败"); null }

    /** 群详情：修改成员角色（设为/取消管理员）。成功后刷新群详情。 */
    fun setMemberRole(chatId: String, uid: String, role: Int) = actionScope.launch {
        try { chatRepo.setMemberRole(chatId, uid, role).getOrThrow(); refreshGroupDetail(chatId) }
        catch (e: AppError) { handleError(e, "修改角色失败") }
    }

    /** 群详情：禁言成员。成功后刷新群详情。 */
    fun muteMember(chatId: String, uid: String, duration: Int = 3600) = actionScope.launch {
        try { chatRepo.muteMember(chatId, uid, duration).getOrThrow(); refreshGroupDetail(chatId) }
        catch (e: AppError) { handleError(e, "禁言失败") }
    }

    /** 群详情：解除禁言。成功后刷新群详情。 */
    fun unmuteMember(chatId: String, uid: String) = actionScope.launch {
        try { chatRepo.unmuteMember(chatId, uid).getOrThrow(); refreshGroupDetail(chatId) }
        catch (e: AppError) { handleError(e, "解除禁言失败") }
    }

    /** 群详情：移除成员。成功后刷新群详情。 */
    fun removeMember(chatId: String, uid: String) = actionScope.launch {
        try { chatRepo.removeMember(chatId, uid).getOrThrow(); refreshGroupDetail(chatId) }
        catch (e: AppError) { handleError(e, "移除成员失败") }
    }

    /** 群详情：更新群公告。成功后刷新群详情。 */
    fun updateGroupNotice(chatId: String, notice: String) = actionScope.launch {
        try { chatRepo.updateGroup(chatId, notice = notice).getOrThrow(); refreshGroupDetail(chatId) }
        catch (e: AppError) { handleError(e, "更新群公告失败") }
    }

    /** 群详情：离开/删除群。成功后由 UI 负责导航回首页。 */
    fun leaveGroup(chatId: String, onLeft: () -> Unit) = actionScope.launch {
        try { chatRepo.deleteChat(chatId).getOrThrow(); onLeft() }
        catch (e: Exception) { handleError(e, "离开群组失败") }
    }

    /** 邀请成员入群。返回是否成功。 */
    suspend fun inviteMembers(chatId: String, uids: List<String>): Boolean = try {
        chatRepo.addMembers(chatId, uids).getOrThrow(); true
    } catch (e: AppError) { handleError(e, "邀请成员失败"); false }

    /** 邀请链接：创建链接，成功后刷新链接列表。返回新 token 或 null。 */
    suspend fun createInviteLink(chatId: String): String? = try {
        val token = chatRepo.createInviteLink(chatId).getOrThrow()
        inviteLinks = chatRepo.listInviteLinks(chatId).getOrThrow()
        token
    } catch (e: AppError) { handleError(e, "创建链接失败"); null }

    /** 邀请链接：撤销链接，成功后刷新链接列表。 */
    fun revokeInviteLink(chatId: String, token: String) = actionScope.launch {
        try { chatRepo.revokeInviteLink(token).getOrThrow(); inviteLinks = chatRepo.listInviteLinks(chatId).getOrThrow() }
        catch (e: AppError) { handleError(e, "撤销链接失败") }
    }

    /** 用户资料页：创建私聊。返回 chatId 或 null。 */
    suspend fun startPersonalChat(uid: String): String? = try {
        chatRepo.createPersonalChat(uid).getOrThrow().chatId
    } catch (e: AppError) { handleError(e, "创建聊天失败"); null }

    /** 转发消息。返回是否成功。 */
    suspend fun forwardMessage(srcChatId: String, srcSeq: Long, targetChatId: String): Boolean = try {
        messageRepo.forwardMessage(srcChatId, srcSeq, targetChatId).getOrThrow(); true
    } catch (e: AppError) { handleError(e, "转发失败"); false }

    /** 搜索用户。返回结果列表（失败返回空列表）。 */
    suspend fun searchUsers(query: String): List<User> = try {
        userRepo.search(query).getOrThrow()
    } catch (e: AppError) { handleError(e, "搜索失败"); emptyList() }

    /** 搜索消息。返回结果列表（失败返回空列表）。 */
    suspend fun searchMessages(query: String): List<Message> = try {
        messageRepo.searchMessages("", query).getOrThrow()
    } catch (e: AppError) { handleError(e, "搜索失败"); emptyList() }

    /** 保存草稿（fire-and-forget，不阻塞调用线程）。 */
    fun saveDraft(chatId: String, draft: String?) = actionScope.launch {
        if (draft.isNullOrEmpty()) return@launch
        try { conversationRepo.setDraft(chatId, draft) }
        catch (e: Exception) { /* 草稿保存失败不打扰用户 */ }
    }

    /** 刷新群详情数据（群信息 + 成员列表）。 */
    private suspend fun refreshGroupDetail(chatId: String) {
        try {
            groupDetailChat = chatRepo.getChat(chatId).getOrThrow()
            groupMembers = chatRepo.getMembers(chatId).getOrThrow()
        } catch (e: AppError) { handleError(e, "刷新群详情失败") }
    }
}

/**
 * 屏幕数据加载键（类型安全）。Android NavHost 各 composable{} 用它触发数据加载。
 * 替代旧的 [SubScreen] 枚举驱动的 [AppState.loadScreenData]。
 */
sealed class ScreenDataKey {
    object Devices : ScreenDataKey()
    object Blacklist : ScreenDataKey()
    object FriendApplies : ScreenDataKey()
    data class GroupDetail(val chatId: String) : ScreenDataKey()
    data class UserProfile(val uid: String) : ScreenDataKey()
    data class InviteLinks(val chatId: String) : ScreenDataKey()
}
