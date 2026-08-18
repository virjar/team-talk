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
        profileUser = null
        isFriend = contactViewModel.contacts.value.any { it.friendUid == uid }
        try {
            profileUser = session.userRepo.getProfile(uid).getOrThrow()
        } catch (e: AppError) {
            reportError(e, "加载用户信息失败")
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
