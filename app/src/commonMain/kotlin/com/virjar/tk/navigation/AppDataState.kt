package com.virjar.tk.navigation

import androidx.compose.runtime.*
import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.*
import com.virjar.tk.viewmodel.ChatViewModel
import com.virjar.tk.viewmodel.ContactViewModel
import com.virjar.tk.viewmodel.ConversationViewModel

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
    }

    /**
     * 准备聊天 ViewModel（纯数据操作，不含导航）。
     * 导航由调用方（Android NavController / Desktop currentScreen）负责。
     */
    fun prepareChat(chatId: String, chatName: String, chatType: Int = ChatType.PERSONAL.code) {
        chatViewModel = ChatViewModel(chatId, imClient, localCache, messageRepo, session.eventProcessor, userSession.uid)
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
