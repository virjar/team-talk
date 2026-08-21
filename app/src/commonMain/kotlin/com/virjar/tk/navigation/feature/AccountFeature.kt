package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.model.Device
import com.virjar.tk.model.ProfilePatch
import com.virjar.tk.model.ProfilePatchValue
import com.virjar.tk.model.User
import com.virjar.tk.viewmodel.ContactViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class ProfileFriendApplyState {
    NONE,
    OUTGOING_PENDING,
    INCOMING_PENDING,
}

internal fun profileFriendApplyState(record: ContactApplyRecord?): ProfileFriendApplyState = when {
    record?.status != ContactApplyRecord.STATUS_PENDING -> ProfileFriendApplyState.NONE
    record.direction == ContactApplyRecord.DIRECTION_OUTGOING -> ProfileFriendApplyState.OUTGOING_PENDING
    record.direction == ContactApplyRecord.DIRECTION_INCOMING -> ProfileFriendApplyState.INCOMING_PENDING
    else -> ProfileFriendApplyState.NONE
}

internal data class FriendApplyHistoryState(
    val records: List<ContactApplyRecord> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = true,
)

internal fun FriendApplyHistoryState.startRefresh(): FriendApplyHistoryState = copy(loading = true)

internal fun FriendApplyHistoryState.finishRefresh(
    records: List<ContactApplyRecord>,
    pageSize: Int,
): FriendApplyHistoryState = FriendApplyHistoryState(
    records = records,
    loading = false,
    hasMore = records.size == pageSize,
)

internal fun FriendApplyHistoryState.failRefresh(): FriendApplyHistoryState = copy(loading = false)

internal data class GenerationCheckedLookup<T>(
    val value: T,
    val observedGeneration: Long,
    val isCurrent: Boolean,
)

/** Repeats one exact lookup when an event invalidates the first response. */
internal suspend fun <T> lookupWithGenerationRetry(
    currentGeneration: () -> Long,
    lookup: suspend () -> T,
): GenerationCheckedLookup<T> {
    val firstGeneration = currentGeneration()
    val firstValue = lookup()
    if (firstGeneration == currentGeneration()) {
        return GenerationCheckedLookup(firstValue, firstGeneration, isCurrent = true)
    }

    val retryGeneration = currentGeneration()
    val retryValue = lookup()
    return GenerationCheckedLookup(
        value = retryValue,
        observedGeneration = retryGeneration,
        isCurrent = retryGeneration == currentGeneration(),
    )
}

/** Account, profile, relationship request and device-management use cases. */
class AccountFeature internal constructor(
    private val session: ClientSession,
    private val contactViewModel: ContactViewModel,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
) {
    /** Prevent a slow profile request for A from replacing the currently routed profile B. */
    private val profileRequestGate = LatestRequestGate<String>()

    val currentDeviceId: String get() = session.deviceId
    var devices by mutableStateOf(emptyList<Device>())
        private set
    var blockedContacts by mutableStateOf(emptyList<Contact>())
        private set
    var friendApplyRecords by mutableStateOf(emptyList<ContactApplyRecord>())
        private set
    var friendApplyRecordsLoading by mutableStateOf(false)
        private set
    var friendApplyRecordsHasMore by mutableStateOf(true)
        private set
    var profileUser by mutableStateOf<User?>(null)
        private set
    var isFriend by mutableStateOf(false)
        private set
    var profileFriendApplyState by mutableStateOf(ProfileFriendApplyState.NONE)
        private set

    private var applyingFriendUids by mutableStateOf(emptySet<String>())
    private var friendApplyStateByUid by mutableStateOf(emptyMap<String, ProfileFriendApplyState>())
    private var friendApplyRecordsGeneration = 0L
    private var friendApplyRecordsInitialized = false
    // AccountFeature 的状态和两个 collector 都由传入的 UI scope 串行持有。
    private var contactApplyEventGeneration = 0L

    init {
        // CONTACT_ACCEPTED/DELETED 会先进入联系人缓存。资料页保持打开时也应立即切换关系
        // 动作，而不是只有重新进入页面才刷新 isFriend。
        scope.launch {
            contactViewModel.contacts.collect { contacts ->
                val uid = profileUser?.uid ?: return@collect
                val nowFriend = contacts.any { it.friendUid == uid }
                if (nowFriend != isFriend) isFriend = nowFriend
                if (nowFriend) updateFriendApplyState(uid, ProfileFriendApplyState.NONE)
            }
        }
        // CONTACT_APPLY 不进入联系人表，因此只观察 contacts 无法让常驻资料页从
        // “添加好友”切到“对方已申请”。事件到达后用精确 RPC 刷新当前目标。
        scope.launch {
            session.eventProcessor.contactEvents.collect {
                contactApplyEventGeneration++
                profileUser?.uid?.let { refreshCurrentProfileApplyState(it) }
                if (friendApplyRecordsInitialized) loadFriendApplies()
            }
        }
    }

    fun hasOutgoingFriendApply(uid: String): Boolean =
        friendApplyStateByUid[uid] == ProfileFriendApplyState.OUTGOING_PENDING

    fun hasIncomingFriendApply(uid: String): Boolean =
        friendApplyStateByUid[uid] == ProfileFriendApplyState.INCOMING_PENDING

    fun isApplyingFriend(uid: String): Boolean = uid in applyingFriendUids

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
        val generation = ++friendApplyRecordsGeneration
        friendApplyRecordsInitialized = true
        applyFriendApplyHistoryState(friendApplyHistoryState().startRefresh())
        when (val result = session.contactRepo.listApplyRecords(beforeId = 0, limit = FRIEND_APPLY_PAGE_SIZE)) {
            is Outcome.Success -> if (generation == friendApplyRecordsGeneration) {
                applyFriendApplyHistoryState(
                    friendApplyHistoryState().finishRefresh(result.value, FRIEND_APPLY_PAGE_SIZE),
                )
            }
            is Outcome.Failure -> if (generation == friendApplyRecordsGeneration) {
                applyFriendApplyHistoryState(friendApplyHistoryState().failRefresh())
                reportError(result.error, "加载好友申请记录失败")
            }
        }
    }

    fun loadMoreFriendApplies() {
        if (friendApplyRecordsLoading || !friendApplyRecordsHasMore || friendApplyRecords.isEmpty()) return
        val generation = friendApplyRecordsGeneration
        val beforeId = friendApplyRecords.last().id
        friendApplyRecordsLoading = true
        scope.launch {
            when (val result = session.contactRepo.listApplyRecords(beforeId, FRIEND_APPLY_PAGE_SIZE)) {
                is Outcome.Success -> if (generation == friendApplyRecordsGeneration) {
                    val knownIds = friendApplyRecords.asSequence().map { it.id }.toHashSet()
                    friendApplyRecords = friendApplyRecords + result.value.filterNot { it.id in knownIds }
                    friendApplyRecordsHasMore = result.value.size == FRIEND_APPLY_PAGE_SIZE
                    friendApplyRecordsLoading = false
                }
                is Outcome.Failure -> if (generation == friendApplyRecordsGeneration) {
                    friendApplyRecordsLoading = false
                    reportError(result.error, "加载更多好友申请记录失败")
                }
            }
        }
    }

    internal suspend fun loadProfile(uid: String) {
        val request = profileRequestGate.begin(uid)
        profileUser = null
        isFriend = false
        profileFriendApplyState = friendApplyStateByUid[uid] ?: ProfileFriendApplyState.NONE

        val loadedProfile = try {
            session.userRepo.getProfile(uid).getOrThrow()
        } catch (e: AppError) {
            if (profileRequestGate.isCurrent(request)) reportError(e, "加载用户信息失败")
            return
        }

        val pendingLookup = lookupWithGenerationRetry(
            currentGeneration = { contactApplyEventGeneration },
            lookup = { session.contactRepo.getPendingApply(uid) },
        )
        if (!profileRequestGate.isCurrent(request)) return

        // 联系人事件可能在资料 RPC 挂起期间到达，提交前读取最新缓存而不是入口快照。
        val loadedFriendState = contactViewModel.contacts.value.any { it.friendUid == uid }
        val lookupIsCurrent = pendingLookup.isCurrent &&
            pendingLookup.observedGeneration == contactApplyEventGeneration
        val loadedApplyState = if (loadedFriendState) {
            ProfileFriendApplyState.NONE
        } else if (!lookupIsCurrent) {
            null
        } else {
            when (val result = pendingLookup.value) {
                is Outcome.Success -> profileFriendApplyState(result.value)
                is Outcome.Failure -> friendApplyStateByUid[uid] ?: ProfileFriendApplyState.NONE
            }
        }

        isFriend = loadedFriendState
        if (loadedApplyState != null) {
            updateFriendApplyState(uid, loadedApplyState)
            profileFriendApplyState = loadedApplyState
        }
        profileUser = loadedProfile

        when {
            !lookupIsCurrent -> scope.launch { refreshCurrentProfileApplyState(uid) }
            pendingLookup.value is Outcome.Failure -> {
                reportError(pendingLookup.value.error, "加载好友申请状态失败")
            }
        }
    }

    private fun friendApplyHistoryState() = FriendApplyHistoryState(
        records = friendApplyRecords,
        loading = friendApplyRecordsLoading,
        hasMore = friendApplyRecordsHasMore,
    )

    private fun applyFriendApplyHistoryState(state: FriendApplyHistoryState) {
        friendApplyRecords = state.records
        friendApplyRecordsLoading = state.loading
        friendApplyRecordsHasMore = state.hasMore
    }

    /**
     * 发起好友申请。只有服务端确认成功后才切换为“已申请”；失败保留当前资料页和原状态。
     */
    fun applyFriend(uid: String, remark: String? = null) {
        if (uid.isBlank() || uid == session.userSession.uid || uid in applyingFriendUids) return
        applyingFriendUids = applyingFriendUids + uid
        scope.launch {
            try {
                val applied = session.contactRepo.apply(uid, remark).getOrThrow()
                if (applied.status == ContactApplyRecord.STATUS_PENDING) {
                    updateFriendApplyState(uid, ProfileFriendApplyState.OUTGOING_PENDING)
                }
            } catch (e: AppError) {
                reportError(e, "申请好友失败")
            } finally {
                applyingFriendUids = applyingFriendUids - uid
            }
        }
    }

    private fun updateFriendApplyState(uid: String, state: ProfileFriendApplyState) {
        friendApplyStateByUid = if (state == ProfileFriendApplyState.NONE) {
            friendApplyStateByUid - uid
        } else {
            friendApplyStateByUid + (uid to state)
        }
        if (profileRequestGate.targets(uid)) profileFriendApplyState = state
    }

    private suspend fun refreshCurrentProfileApplyState(uid: String) {
        val result = session.contactRepo.getPendingApply(uid)
        if (!profileRequestGate.targets(uid) || profileUser?.uid != uid) return
        when (result) {
            is Outcome.Success -> {
                val state = if (contactViewModel.contacts.value.any { it.friendUid == uid }) {
                    ProfileFriendApplyState.NONE
                } else {
                    profileFriendApplyState(result.value)
                }
                updateFriendApplyState(uid, state)
            }
            is Outcome.Failure -> reportError(result.error, "刷新好友申请状态失败")
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
        updateFriendApplyState(uid, ProfileFriendApplyState.NONE)
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
        session.userRepo.updateProfile(
            ProfilePatch(
                name = ProfilePatchValue.Set(name),
                phone = ProfilePatchValue.Set(phone),
            ),
        ).getOrThrow()
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

    suspend fun acceptFriendApply(token: String): Boolean {
        return try {
            val apply = session.contactRepo.accept(token).getOrThrow()
            updateFriendApplyState(apply.fromUid, ProfileFriendApplyState.NONE)
            loadFriendApplies()
            // CONTACT_ACCEPTED 事件可能断线或延迟；成功响应后主动拉取权威好友列表。
            contactViewModel.refresh()
            contactViewModel.refreshPendingApplyCount()
            true
        } catch (e: AppError) {
            reportError(e, "接受申请失败")
            false
        }
    }

    suspend fun rejectFriendApply(token: String): Boolean {
        return try {
            val apply = session.contactRepo.reject(token).getOrThrow()
            updateFriendApplyState(apply.fromUid, ProfileFriendApplyState.NONE)
            loadFriendApplies()
            contactViewModel.refreshPendingApplyCount()
            true
        } catch (e: AppError) {
            reportError(e, "拒绝申请失败")
            false
        }
    }

    private companion object {
        const val FRIEND_APPLY_PAGE_SIZE = 50
    }
}
