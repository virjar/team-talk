package com.virjar.tk.app.navigation.feature.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 拥有草稿持久化、恢复簿记和稳定的本地 tab 实例分配。 */
internal class DocumentWorkspaceDraftCollaboration(
    private val ownerKey: DocumentDraftOwnerKey,
    private val draftStore: DocumentDraftStore,
    val lifecycleBridge: DocumentDraftLifecycleBridge,
) {
    private var restorationLoaded = false
    private var pendingRestoration: DocumentWorkspaceDraftSnapshot? = null
    private var tabInstanceSequence = 0L

    suspend fun loadInitialRestoration(): DocumentWorkspaceDraftSnapshot? {
        if (restorationLoaded) return pendingRestoration
        val restored = withContext(Dispatchers.Default) {
            draftStore.restore(ownerKey)?.normalized()
        }
        pendingRestoration = restored
        restorationLoaded = true
        trackRestoredTabs(restored?.tabs.orEmpty())
        return restored
    }

    fun restorationApplied() {
        pendingRestoration = null
    }

    fun trackRestoredTabs(tabs: List<DocumentTabState>) {
        tabInstanceSequence = maxOf(
            tabInstanceSequence,
            tabs.maxOfOrNull(DocumentTabState::instanceId) ?: 0L,
        )
    }

    fun save(
        tabs: List<DocumentTabState>,
        activeTabId: String?,
        selectedSpaceId: String?,
        pendingSpaceCreates: List<DocumentSpaceCreateRequest>,
        pendingDocumentCreates: List<PendingDocumentCreateCommand>,
        pendingDestructiveIntents: List<DocumentDestructiveIntent> = emptyList(),
    ): Boolean = draftStore.save(
        key = ownerKey,
        tabs = tabs,
        activeTabId = activeTabId,
        selectedSpaceId = selectedSpaceId,
        pendingSpaceCreates = pendingSpaceCreates,
        pendingDocumentCreates = pendingDocumentCreates,
        pendingDestructiveIntents = pendingDestructiveIntents,
    )

    fun captureLatest(): Boolean = lifecycleBridge.captureLatest()

    suspend fun flush(): Boolean = withContext(Dispatchers.Default) { draftStore.flush() }

    suspend fun tombstone(recoveryKeys: Set<String>): Boolean = withContext(Dispatchers.Default) {
        draftStore.tombstone(ownerKey, recoveryKeys)
    }

    fun nextTabInstanceId(): Long {
        check(tabInstanceSequence < Long.MAX_VALUE) { "Document tab instance sequence exhausted" }
        return ++tabInstanceSequence
    }
}

/** 在本地草稿快照变为可观察之后，打开远程工作区的结果。 */
internal data class DraftFirstRemoteLoad<T>(
    val restoration: DocumentWorkspaceDraftSnapshot?,
    val remoteValue: T,
)

/**
 * 在启动任何远程工作区请求之前，发布我们承诺离线拥有的唯一状态——持久化的 dirty/creating tab。
 * 把这个排序保持在一个挂起接缝中，使失败或无限挂起的 list-spaces 请求
 * 不可能隐藏本地正文。
 */
internal suspend fun <T> publishPersistedDraftBeforeRemoteLoad(
    rawSnapshot: DocumentWorkspaceDraftSnapshot?,
    publish: (DocumentWorkspaceDraftSnapshot) -> Unit,
    loadRemote: suspend () -> T,
): DraftFirstRemoteLoad<T> {
    val restoration = rawSnapshot?.normalized()
    restoration?.takeIf { it.tabs.isNotEmpty() }?.let(publish)
    return DraftFirstRemoteLoad(
        restoration = restoration,
        remoteValue = loadRemote(),
    )
}
