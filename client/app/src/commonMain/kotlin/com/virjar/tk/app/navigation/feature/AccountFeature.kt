package com.virjar.tk.app.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.PendingContactDecisionType
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.protocol.model.Device
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.ProfilePatchValue
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.viewmodel.ContactViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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

/** 当事件使第一次响应失效时，重复一次完全相同的查询。 */
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

internal fun authenticatedProfileRefreshTargets(
    currentUid: String,
    openProfileUid: String?,
): List<String> = listOfNotNull(
    currentUid.takeIf { it.isNotBlank() },
    openProfileUid,
).distinct()

/**
 * 一次成功的资料修改在服务器上已经是持久的。随后的读取只是收敛本地投影，
 * 因此它的失败绝不能把那次已完成的修改变成一次被报告的保存失败。
 * [UserRepository.getProfile] 拥有快照租约，该租约把这次刷新与更新的 USER_UPDATED 事件隔离开。
 */
internal suspend fun saveProfileAndRefreshProjection(
    save: suspend () -> Outcome<Unit>,
    refresh: suspend () -> Outcome<User?>,
): Outcome<Unit> {
    val saveResult = save()
    if (saveResult is Outcome.Success) refresh()
    return saveResult
}

internal data class RecoveredContactDecisionRefreshPlan(
    val peerUid: String,
    val refreshProfile: Boolean,
    val refreshHistory: Boolean,
    val refreshContacts: Boolean,
)

internal fun recoveredContactDecisionRefreshPlan(
    peerUid: String,
    decision: PendingContactDecisionType,
    openProfileUid: String?,
    historyInitialized: Boolean,
): RecoveredContactDecisionRefreshPlan = RecoveredContactDecisionRefreshPlan(
    peerUid = peerUid,
    refreshProfile = openProfileUid == peerUid,
    refreshHistory = historyInitialized,
    refreshContacts = decision == PendingContactDecisionType.ACCEPT,
)

/** 账号、资料、好友关系请求和设备管理用例。 */
class AccountFeature internal constructor(
    private val session: ClientSession,
    private val contactViewModel: ContactViewModel,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val localData: UiLocalDataBoundary,
) {
    /** 防止针对 A 的慢速资料请求替换当前路由到的资料 B。 */
    private val profileRequestGate = LatestRequestGate<String>()
    private var profileObserverJob: Job? = null
    private var authenticatedProfileRefreshJob: Job? = null
    private var profileTargetUid: String? = null

    val currentDeviceId: String get() = session.deviceId
    var currentUser by mutableStateOf(currentUserFallback())
        private set
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
        val currentUid = session.userSession.uid
        if (currentUid.isNotBlank()) {
            val cache = session.localCache
            scope.launch {
                localData.projection { cache.observeUser(currentUid) }.collect { user ->
                    currentUser = user ?: currentUserFallback()
                }
            }
        }
        // CONTACT_ACCEPTED/DELETED 会先进入联系人缓存。资料页保持打开时也应立即切换关系
        // 动作，而不是只有重新进入页面才刷新 isFriend。
        scope.launch {
            contactViewModel.contacts.collect { contacts ->
                val uid = profileTargetUid ?: return@collect
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
                profileTargetUid?.let { refreshCurrentProfileApplyState(it) }
                if (friendApplyRecordsInitialized) loadFriendApplies()
            }
        }
        scope.launch {
            session.contactDecisionRecoveryCompletions.collect { completion ->
                val plan = recoveredContactDecisionRefreshPlan(
                    peerUid = completion.peerUid,
                    decision = completion.decision,
                    openProfileUid = profileTargetUid,
                    historyInitialized = friendApplyRecordsInitialized,
                )
                // 只有在确切的持久槽位被确认之后，完成事件才会发出。
                // 先清除乐观的资料状态，然后拉取每一个当前可及的、
                // 可能错过了原始响应/事件的服务器拥有的投影。
                contactApplyEventGeneration++
                updateFriendApplyState(plan.peerUid, ProfileFriendApplyState.NONE)
                if (plan.refreshProfile) refreshCurrentProfileApplyState(plan.peerUid)
                if (plan.refreshHistory) loadFriendApplies()
                if (plan.refreshContacts) contactViewModel.refresh()
                contactViewModel.refreshPendingApplyCount()
            }
        }
        scope.launch {
            var authenticated = session.connectionState.value == ConnectionState.AUTHENTICATED
            session.connectionState.collect { state ->
                val nowAuthenticated = state == ConnectionState.AUTHENTICATED
                if (nowAuthenticated && !authenticated) {
                    scheduleAuthenticatedProfileRefresh()
                }
                authenticated = nowAuthenticated
            }
        }
        if (session.connectionState.value == ConnectionState.AUTHENTICATED) {
            scheduleAuthenticatedProfileRefresh()
        }
    }

    fun hasOutgoingFriendApply(uid: String): Boolean =
        friendApplyStateByUid[uid] == ProfileFriendApplyState.OUTGOING_PENDING

    fun hasIncomingFriendApply(uid: String): Boolean =
        friendApplyStateByUid[uid] == ProfileFriendApplyState.INCOMING_PENDING

    fun isApplyingFriend(uid: String): Boolean = uid in applyingFriendUids

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
        bindProfileProjection(uid)
        isFriend = contactViewModel.contacts.value.any { it.friendUid == uid }
        profileFriendApplyState = friendApplyStateByUid[uid] ?: ProfileFriendApplyState.NONE
        if (isFriend) updateFriendApplyState(uid, ProfileFriendApplyState.NONE)
        refreshProfile(uid)
    }

    private fun bindProfileProjection(uid: String) {
        profileTargetUid = uid
        profileObserverJob?.cancel()
        val cache = session.localCache
        if (profileUser?.uid != uid) profileUser = null
        profileObserverJob = scope.launch {
            localData.projection { cache.observeUser(uid) }.collect { user ->
                if (profileTargetUid == uid) profileUser = user
            }
        }
    }

    private suspend fun refreshProfile(uid: String) {
        val request = profileRequestGate.begin(uid)
        if (session.connectionState.value != ConnectionState.AUTHENTICATED) return

        when (val result = localData.run { session.userRepo.getProfile(uid) }) {
            is Outcome.Success -> Unit // RPC 只收敛 LocalCache；UI 状态由观察者拥有。
            is Outcome.Failure -> {
                if (
                    profileRequestGate.isCurrent(request) &&
                    shouldReportCacheRefreshFailure(
                        result.error,
                        profileTargetUid == uid && profileUser != null,
                    )
                ) {
                    reportError(result.error, "加载用户信息失败")
                }
                return
            }
        }
        if (!profileRequestGate.isCurrent(request)) return

        if (uid == session.userSession.uid) {
            isFriend = false
            updateFriendApplyState(uid, ProfileFriendApplyState.NONE)
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

        when {
            !lookupIsCurrent -> scope.launch { refreshCurrentProfileApplyState(uid) }
            pendingLookup.value is Outcome.Failure -> {
                if (
                    shouldReportCacheRefreshFailure(
                        pendingLookup.value.error,
                        friendApplyStateByUid.containsKey(uid) || loadedFriendState,
                    )
                ) {
                    reportError(pendingLookup.value.error, "加载好友申请状态失败")
                }
            }
        }
    }

    private fun scheduleAuthenticatedProfileRefresh() {
        authenticatedProfileRefreshJob?.cancel()
        authenticatedProfileRefreshJob = scope.launch {
            val openTarget = profileTargetUid
            coroutineScope {
                authenticatedProfileRefreshTargets(session.userSession.uid, openTarget).forEach { uid ->
                    launch {
                        if (uid == openTarget) {
                            refreshProfile(uid)
                        } else {
                            // 我/头像也是一个 LocalCache 投影。刷新当前已认证 uid
                            // 绝不能依赖于资料页面是否打开。
                            localData.run { session.userRepo.getProfile(uid) }
                        }
                    }
                }
            }
        }
    }

    private fun currentUserFallback(): User? {
        val userSession = session.userSession
        val uid = userSession.uid
        if (uid.isBlank()) return null
        return User(
            uid = uid,
            username = userSession.username ?: "",
            name = userSession.name ?: "",
            revision = 0L,
        )
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
        if (session.connectionState.value != ConnectionState.AUTHENTICATED) return
        val result = session.contactRepo.getPendingApply(uid)
        if (!profileRequestGate.targets(uid) || profileTargetUid != uid) return
        when (result) {
            is Outcome.Success -> {
                val state = if (contactViewModel.contacts.value.any { it.friendUid == uid }) {
                    ProfileFriendApplyState.NONE
                } else {
                    profileFriendApplyState(result.value)
                }
                updateFriendApplyState(uid, state)
            }
            is Outcome.Failure -> if (
                shouldReportCacheRefreshFailure(
                    result.error,
                    friendApplyStateByUid.containsKey(uid) || isFriend,
                )
            ) {
                reportError(result.error, "刷新好友申请状态失败")
            }
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
        localData.run { session.localCache.deleteContact(uid) }
        updateFriendApplyState(uid, ProfileFriendApplyState.NONE)
        if (profileRequestGate.targets(uid) && profileTargetUid == uid) isFriend = false

        var refreshError: AppError? = null
        try {
            // listFriends 会把服务端现有好友写回 LocalCache；最后再次删除目标，避免并发旧列表
            // 将刚拉黑的联系人短暂写回。
            localData.run { session.contactRepo.listFriends().getOrThrow() }
        } catch (e: AppError) {
            refreshError = e
        } finally {
            localData.run { session.localCache.deleteContact(uid) }
        }

        try {
            blockedContacts = session.contactRepo.listBlacklist().getOrThrow()
        } catch (e: AppError) {
            if (refreshError == null) refreshError = e
        }

        refreshError?.let { reportError(it, "已加入黑名单，但刷新状态失败") }
        onBlocked?.invoke()
    }

    suspend fun saveProfile(
        name: String,
        phone: String?,
        avatar: ProfilePatchValue<Attachment?> = ProfilePatchValue.Unchanged,
    ): Boolean {
        val result = saveProfileAndRefreshProjection(
            save = {
                session.userRepo.updateProfile(
                    ProfilePatch(
                        name = ProfilePatchValue.Set(name),
                        avatar = avatar,
                        phone = ProfilePatchValue.Set(phone),
                    ),
                )
            },
            refresh = {
                localData.run { session.userRepo.getProfile(session.userSession.uid) }
            },
        )
        return when (result) {
            is Outcome.Success -> true
            is Outcome.Failure -> {
                reportError(result.error, "保存失败")
                false
            }
        }
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
            val apply = localData.run { session.contactRepo.accept(token).getOrThrow() }
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
            val apply = localData.run { session.contactRepo.reject(token).getOrThrow() }
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
