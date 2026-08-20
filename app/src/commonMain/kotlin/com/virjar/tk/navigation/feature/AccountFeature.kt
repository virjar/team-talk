package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.Device
import com.virjar.tk.model.User
import com.virjar.tk.viewmodel.ContactViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Account, profile, relationship request and device-management use cases. */
class AccountFeature internal constructor(
    private val session: ClientSession,
    private val contactViewModel: ContactViewModel,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
) {
    /** Prevent a slow profile request for A from replacing the currently routed profile B. */
    private val profileRequestGate = GroupRequestGate<String>()

    val currentDeviceId: String get() = session.deviceId
    var devices by mutableStateOf(emptyList<Device>())
        private set
    var blockedContacts by mutableStateOf(emptyList<Contact>())
        private set
    var applies by mutableStateOf(emptyList<ContactApply>())
        private set
    var profileUser by mutableStateOf<User?>(null)
        private set
    var isFriend by mutableStateOf(false)
        private set

    val currentUser: User?
        get() {
            val userSession = session.userSession
            val uid = userSession.uid
            if (uid.isBlank()) return null
            return session.localCache.getUser(uid) ?: User(
                uid = uid,
                username = userSession.username ?: "",
                name = userSession.name ?: "",
            )
        }

    internal suspend fun loadDevices() {
        try {
            devices = session.deviceRepo.listDevices().getOrThrow()
        } catch (e: AppError) {
            reportError(e, "加载设备列表失败")
        }
    }

    internal suspend fun loadBlacklist() {
        try {
            blockedContacts = session.contactRepo.listBlacklist().getOrThrow()
        } catch (e: AppError) {
            reportError(e, "加载黑名单失败")
        }
    }

    internal suspend fun loadFriendApplies() {
        try {
            applies = session.contactRepo.listApplies().getOrThrow()
        } catch (e: AppError) {
            reportError(e, "加载好友申请失败")
        }
    }

    internal suspend fun loadProfile(uid: String) {
        val request = profileRequestGate.begin(uid)
        profileUser = null
        isFriend = false
        val loadedFriendState = contactViewModel.contacts.value.any { it.friendUid == uid }
        try {
            val loadedProfile = session.userRepo.getProfile(uid).getOrThrow()
            if (!profileRequestGate.isCurrent(request)) return
            profileUser = loadedProfile
            isFriend = loadedFriendState
        } catch (e: AppError) {
            if (profileRequestGate.isCurrent(request)) reportError(e, "加载用户信息失败")
        }
    }

    fun kickDevice(deviceId: String) = scope.launch {
        try {
            session.deviceRepo.kickDevice(deviceId).getOrThrow()
            devices = session.deviceRepo.listDevices().getOrThrow()
        } catch (e: AppError) {
            reportError(e, "踢出设备失败")
        }
    }

    fun unblockContact(uid: String) = scope.launch {
        try {
            session.contactRepo.removeFromBlacklist(uid).getOrThrow()
            blockedContacts = session.contactRepo.listBlacklist().getOrThrow()
        } catch (e: AppError) {
            reportError(e, "移出黑名单失败")
        }
    }

    /**
     * 将用户加入黑名单，并让本地联系人投影与黑名单投影在操作完成后立即收敛。
     *
     * 服务端拉黑不会发送 CONTACT_DELETED 事件，因此这里必须主动移除本地联系人；否则
     * 联系人页面会一直保留已经被拉黑的用户，直到其他事件碰巧触发一次完整刷新。
     */
    fun blockContact(uid: String, onBlocked: (() -> Unit)? = null) = scope.launch {
        try {
            session.contactRepo.blacklist(uid).getOrThrow()
        } catch (e: AppError) {
            reportError(e, "加入黑名单失败")
            return@launch
        }

        // 先更新当前页面可见状态；后续刷新失败也不能把已完成的拉黑操作伪装成仍是好友。
        session.localCache.deleteContact(uid)
        if (profileRequestGate.targets(uid) && profileUser?.uid == uid) isFriend = false

        var refreshError: AppError? = null
        try {
            // listFriends 会把服务端现有好友写回 LocalCache；最后再次删除目标，避免并发旧列表
            // 将刚拉黑的联系人短暂写回。
            session.contactRepo.listFriends().getOrThrow()
        } catch (e: AppError) {
            refreshError = e
        } finally {
            session.localCache.deleteContact(uid)
        }

        try {
            blockedContacts = session.contactRepo.listBlacklist().getOrThrow()
        } catch (e: AppError) {
            if (refreshError == null) refreshError = e
        }

        refreshError?.let { reportError(it, "已加入黑名单，但刷新状态失败") }
        onBlocked?.invoke()
    }

    suspend fun saveProfile(name: String, phone: String?): Boolean = try {
        session.userRepo.updateProfile(name = name, phone = phone).getOrThrow()
        true
    } catch (e: AppError) {
        reportError(e, "保存失败")
        false
    }

    suspend fun changePassword(old: String, new: String): Boolean = try {
        session.userRepo.changePassword(old, new).getOrThrow()
        true
    } catch (e: AppError) {
        reportError(e, "修改密码失败")
        false
    }

    fun acceptFriendApply(token: String) = scope.launch {
        try {
            session.contactRepo.accept(token).getOrThrow()
            applies = session.contactRepo.listApplies().getOrThrow()
            contactViewModel.refreshPendingApplyCount()
        } catch (e: AppError) {
            reportError(e, "接受申请失败")
        }
    }

    fun rejectFriendApply(token: String) = scope.launch {
        try {
            session.contactRepo.reject(token).getOrThrow()
            applies = session.contactRepo.listApplies().getOrThrow()
            contactViewModel.refreshPendingApplyCount()
        } catch (e: AppError) {
            reportError(e, "拒绝申请失败")
        }
    }
}
