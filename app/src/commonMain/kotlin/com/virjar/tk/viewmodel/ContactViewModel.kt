package com.virjar.tk.viewmodel

import com.virjar.tk.client.LocalCache
import com.virjar.tk.model.Contact
import com.virjar.tk.repository.ContactRepository
import com.virjar.tk.util.AppLog
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
    private val myUid: String = "",
    contactEvents: Flow<Unit> = emptyFlow(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : BaseViewModel(dispatcher) {

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** 联系人列表（过滤掉自己，避免通讯录出现自己）。 */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _pendingApplyCount = MutableStateFlow(0)
    val pendingApplyCount: StateFlow<Int> = _pendingApplyCount.asStateFlow()

    /** Manual refreshes and contact events share one conflated, lifecycle-owned request stream. */
    private val pendingApplyRefreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            localCache.observeContacts().collect { list ->
                // 过滤掉自己（服务端 CONTACT_ACCEPTED 可能误存自己为自己好友）
                _contacts.value = if (myUid.isNotBlank()) list.filter { it.friendUid != myUid } else list
            }
        }
        _contacts.value = localCache.getContacts().let { if (myUid.isNotBlank()) it.filter { c -> c.friendUid != myUid } else it }
        refresh()
        scope.launch {
            merge(pendingApplyRefreshRequests.receiveAsFlow(), contactEvents).collectLatest {
                loadPendingApplyCount()
            }
        }
        refreshPendingApplyCount()
    }

    fun refresh() {
        scope.launch {
            runViewModelAction("刷新联系人失败") {
                contactRepo.listFriends().getOrThrow()
            }
        }
    }

    /** 刷新待处理好友申请数（用于红点/徽标）。 */
    fun refreshPendingApplyCount() {
        pendingApplyRefreshRequests.trySend(Unit)
    }

    private suspend fun loadPendingApplyCount() {
        try {
            val applies = contactRepo.listPendingApplies().getOrThrow()
            _pendingApplyCount.value = applies.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            AppLog.trace("ContactVM", "Failed to refresh pending apply count: ${throwable.message}")
        }
    }

    fun apply(toUid: String, remark: String? = null) {
        scope.launch {
            runViewModelAction("申请好友失败") {
                contactRepo.apply(toUid, remark).getOrThrow()
            }
        }
    }

    fun deleteFriend(friendUid: String) {
        scope.launch {
            runViewModelAction("删除好友失败") {
                contactRepo.deleteFriend(friendUid).getOrThrow()
            }
        }
    }

    fun updateRemark(friendUid: String, remark: String?) {
        scope.launch {
            runViewModelAction("修改备注失败") {
                contactRepo.setRemark(friendUid, remark).getOrThrow()
            }
        }
    }

    override fun destroy() {
        pendingApplyRefreshRequests.close()
        super.destroy()
    }
}
