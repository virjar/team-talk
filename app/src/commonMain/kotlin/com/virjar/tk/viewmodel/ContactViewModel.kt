package com.virjar.tk.viewmodel

import com.virjar.tk.client.LocalCache
import com.virjar.tk.model.Contact
import com.virjar.tk.repository.ContactRepository
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 联系人 ViewModel。
 */
class ContactViewModel(
    private val localCache: LocalCache,
    private val contactRepo: ContactRepository,
    private val myUid: String = "",
) : BaseViewModel() {

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** 联系人列表（过滤掉自己，避免通讯录出现自己）。 */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _pendingApplyCount = MutableStateFlow(0)
    val pendingApplyCount: StateFlow<Int> = _pendingApplyCount.asStateFlow()

    init {
        scope.launch {
            localCache.observeContacts().collect { list ->
                // 过滤掉自己（服务端 CONTACT_ACCEPTED 可能误存自己为自己好友）
                _contacts.value = if (myUid.isNotBlank()) list.filter { it.friendUid != myUid } else list
            }
        }
        _contacts.value = localCache.getContacts().let { if (myUid.isNotBlank()) it.filter { c -> c.friendUid != myUid } else it }
        refresh()
        refreshPendingApplyCount()
    }

    fun refresh() {
        scope.launch {
            try { contactRepo.listFriends() }
            catch (e: Exception) { setError("刷新联系人失败: ${e.message}") }
        }
    }

    /** 刷新待处理好友申请数（用于红点/徽标）。 */
    fun refreshPendingApplyCount() {
        scope.launch {
            try {
                val applies = contactRepo.listApplies().getOrThrow()
                _pendingApplyCount.value = applies.size
            } catch (e: Exception) {
                AppLog.trace("ContactVM", "Failed to refresh pending apply count: ${e.message}")
            }
        }
    }

    fun apply(toUid: String, remark: String? = null) {
        scope.launch {
            try { contactRepo.apply(toUid, remark) }
            catch (e: Exception) { setError("申请好友失败: ${e.message}") }
        }
    }

    fun deleteFriend(friendUid: String) {
        scope.launch {
            try { contactRepo.deleteFriend(friendUid) }
            catch (e: Exception) { setError("删除好友失败: ${e.message}") }
        }
    }

    fun updateRemark(friendUid: String, remark: String?) {
        scope.launch {
            try { contactRepo.setRemark(friendUid, remark) }
            catch (e: Exception) { setError("修改备注失败: ${e.message}") }
        }
    }
}
