package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.FriendPresence
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.ContactRepository
import com.virjar.tk.shared.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 联系人 ViewModel。
 */
class ContactViewModel(
    private val localCache: LocalCache,
    private val contactRepo: ContactRepository,
    private val connectionState: StateFlow<ConnectionState>,
    val friendPresenceByUid: StateFlow<Map<String, FriendPresence>> = MutableStateFlow(emptyMap()),
    private val myUid: String = "",
    contactEvents: Flow<Unit> = emptyFlow(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    onAuthExpired: () -> Unit = {},
    private val localData: UiLocalDataBoundary = UiLocalDataBoundary(dispatcher),
) : BaseViewModel(dispatcher, onAuthExpired) {

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** 联系人列表（过滤掉自己，避免通讯录出现自己）。 */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _pendingApplyCount = MutableStateFlow(0)
    val pendingApplyCount: StateFlow<Int> = _pendingApplyCount.asStateFlow()

    /** 手动刷新和联系人事件共享一条合并的、生命周期拥有的请求流。 */
    private val pendingApplyRefreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            localData.projection(localCache::observeContacts).collect { list ->
                // 过滤掉自己（服务端 CONTACT_ACCEPTED 可能误存自己为自己好友）
                _contacts.value = if (myUid.isNotBlank()) list.filter { it.friendUid != myUid } else list
            }
        }
        scope.launch {
            merge(
                pendingApplyRefreshRequests.receiveAsFlow(),
                contactEvents,
            ).collectLatest {
                if (connectionState.value == ConnectionState.AUTHENTICATED) {
                    loadPendingApplyCount()
                }
            }
        }
        scope.launch {
            connectionState.collectLatest { state ->
                if (state == ConnectionState.AUTHENTICATED) {
                    loadContacts()
                    loadPendingApplyCount()
                }
            }
        }
    }

    fun refresh() {
        if (connectionState.value != ConnectionState.AUTHENTICATED) return
        scope.launch { loadContacts() }
    }

    private suspend fun loadContacts() = runViewModelAction("刷新联系人失败") {
        localData.run { contactRepo.listFriends().getOrThrow() }
    }

    /** 刷新待处理好友申请数（用于红点/徽标）。 */
    fun refreshPendingApplyCount() {
        pendingApplyRefreshRequests.trySend(Unit)
    }

    private suspend fun loadPendingApplyCount() {
        try {
            val applies = localData.run { contactRepo.listPendingApplies().getOrThrow() }
            _pendingApplyCount.value = applies.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: com.virjar.tk.shared.AppError.AuthExpired) {
            handleAuthExpired()
        } catch (throwable: Exception) {
            AppLog.trace("ContactVM", "Failed to refresh pending apply count: ${throwable.message}")
        }
    }

    fun apply(toUid: String, remark: String? = null) {
        scope.launch {
            runViewModelAction("申请好友失败") {
                localData.run { contactRepo.apply(toUid, remark).getOrThrow() }
            }
        }
    }

    fun deleteFriend(friendUid: String) {
        scope.launch {
            runViewModelAction("删除好友失败") {
                localData.run { contactRepo.deleteFriend(friendUid).getOrThrow() }
            }
        }
    }

    override fun destroy() {
        pendingApplyRefreshRequests.close()
        super.destroy()
    }
}
