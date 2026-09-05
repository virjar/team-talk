package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 当前渲染的、有界的 GlobalSearch 结果行的存活规范用户。 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchUserViewModel(
    private val localCache: LocalCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val localData: UiLocalDataBoundary = UiLocalDataBoundary(dispatcher),
) : BaseViewModel(dispatcher) {
    private val displayedUserUids = MutableStateFlow<List<String>>(emptyList())
    private val _users = MutableStateFlow<Map<String, User?>>(emptyMap())
    /** 一个存在的 null 记录了一条观察到的规范投影被 reset/checkpoint 移除。 */
    val users: StateFlow<Map<String, User?>> = _users.asStateFlow()

    init {
        scope.launch {
            displayedUserUids
                .flatMapLatest(::observeUsers)
                .collect { _users.value = it }
        }
    }

    /** 替换确切的可见非联系人工作集；LocalCache 拥有 revision 选择。 */
    fun bindDisplayedUserUids(uids: List<String>) {
        displayedUserUids.value = uids.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_DISPLAYED_SEARCH_USERS)
            .toList()
    }

    private fun observeUsers(uids: List<String>): Flow<Map<String, User?>> {
        if (uids.isEmpty()) return flowOf(emptyMap())
        return combine(
            uids.map { uid ->
                localData.projection { localCache.observeUser(uid) }
                    .map { user -> uid to user }
            },
        ) { projections ->
            buildMap {
                projections.forEach { (uid, user) -> put(uid, user) }
            }
        }
    }

    private companion object {
        const val MAX_DISPLAYED_SEARCH_USERS = 40
    }
}
