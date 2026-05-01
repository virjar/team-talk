package com.virjar.tk.viewmodel

import com.virjar.tk.client.UserContext
import com.virjar.tk.dto.*
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContactsState(
    val friends: List<FriendDto> = emptyList(),
    val onlineStatus: Map<String, Boolean> = emptyMap(),
    val friendAvatarMap: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String = "",
)

data class SearchState(
    val query: String = "",
    val results: List<UserDto> = emptyList(),
    val isSearching: Boolean = false,
    val error: String = "",
)

data class FriendAppliesState(
    val applies: List<FriendApplyDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
)

class ContactsViewModel(
    private val ctx: UserContext,
) {
    private val contactRepo = ctx.contactRepo
    private val userRepo = ctx.userRepo

    private val _contactsState = MutableStateFlow(ContactsState())
    val contactsState: StateFlow<ContactsState> = _contactsState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _appliesState = MutableStateFlow(FriendAppliesState())
    val appliesState: StateFlow<FriendAppliesState> = _appliesState.asStateFlow()

    suspend fun loadFriends() {
        // Phase 1: 先从本地 DB 读取缓存数据并立即展示
        val cached = try {
            contactRepo.getCachedFriends()
        } catch (e: Exception) {
            AppLog.w("ContactsVM", "getCachedFriends failed", e)
            emptyList()
        }
        if (cached.isNotEmpty()) {
            _contactsState.value = _contactsState.value.copy(friends = cached, isLoading = true, error = "")
        } else {
            _contactsState.value = _contactsState.value.copy(isLoading = true, error = "")
        }

        // Phase 2: 后台网络同步
        try {
            val friends = contactRepo.getFriends()
            val uids = friends.map { it.friendUid }
            val onlineStatus = try {
                userRepo.getOnlineStatus(uids)
            } catch (e: Exception) {
                AppLog.w("ContactsVM", "getOnlineStatus failed", e)
                emptyMap()
            }
            // Preload friend avatars
            val avatarMap = mutableMapOf<String, String>()
            friends.forEach { friend ->
                try {
                    val user = userRepo.getUser(friend.friendUid)
                    if (user.avatar.isNotEmpty()) {
                        avatarMap[friend.friendUid] = user.avatar
                    }
                } catch (e: Exception) {
                    AppLog.w("ContactsVM", "Avatar preload failed for ${friend.friendUid}", e)
                }
            }
            _contactsState.value = ContactsState(
                friends = friends,
                onlineStatus = onlineStatus,
                friendAvatarMap = avatarMap,
            )
        } catch (e: Exception) {
            AppLog.e("ContactsVM", "loadFriends failed", e)
            if (cached.isNotEmpty()) {
                _contactsState.value = _contactsState.value.copy(isLoading = false)
            } else {
                _contactsState.value = _contactsState.value.copy(isLoading = false, error = e.toUserMessage())
            }
        }
    }

    suspend fun search(query: String) {
        _searchState.value = SearchState(query = query, isSearching = true)
        try {
            val results = contactRepo.searchUsers(query)
            _searchState.value = SearchState(query = query, results = results)
        } catch (e: Exception) {
            AppLog.e("ContactsVM", "search failed: q=$query", e)
            _searchState.value = _searchState.value.copy(isSearching = false, error = e.toUserMessage())
        }
    }

    suspend fun applyFriend(toUid: String, remark: String = ""): Boolean {
        return try {
            contactRepo.applyFriend(toUid, remark)
            true
        } catch (e: Exception) {
            AppLog.e("ContactsVM", "applyFriend failed: toUid=$toUid", e)
            false
        }
    }

    suspend fun loadApplies() {
        _appliesState.value = _appliesState.value.copy(isLoading = true)
        try {
            val applies = contactRepo.getApplies()
            _appliesState.value = FriendAppliesState(applies = applies)
        } catch (e: Exception) {
            AppLog.e("ContactsVM", "loadApplies failed", e)
            _appliesState.value = _appliesState.value.copy(isLoading = false, error = e.toUserMessage())
        }
    }

    suspend fun acceptApply(token: String): Boolean {
        return try {
            contactRepo.acceptApply(token)
            loadApplies() // refresh
            loadFriends() // refresh friend list
            true
        } catch (e: Exception) {
            AppLog.e("ContactsVM", "acceptApply failed", e)
            false
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState()
    }

    suspend fun deleteFriend(uid: String): Boolean {
        return try {
            contactRepo.deleteFriend(uid)
            loadFriends()
            true
        } catch (e: Exception) {
            AppLog.e("ContactsVM", "deleteFriend failed: uid=$uid", e)
            false
        }
    }

    suspend fun updateRemark(uid: String, remark: String): Boolean {
        return try {
            contactRepo.updateRemark(uid, remark)
            loadFriends()
            true
        } catch (e: Exception) {
            AppLog.e("ContactsVM", "updateRemark failed: uid=$uid", e)
            false
        }
    }
}
