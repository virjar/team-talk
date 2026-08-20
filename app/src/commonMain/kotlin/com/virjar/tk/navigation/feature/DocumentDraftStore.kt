package com.virjar.tk.navigation.feature

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Platform storage for the opaque, versioned document-draft payload.
 *
 * [write] may coalesce work off the UI thread. [delete] and [clearAll] must synchronously
 * invalidate pending writes so a completed save/logout can never resurrect an older body.
 */
interface DocumentDraftPersistence {
    fun read(uid: String): String?
    fun write(uid: String, payload: () -> String): Boolean
    fun delete(uid: String): Boolean
    fun clearAll(): Boolean
    fun flush(): Boolean = true
}

/**
 * Synchronous bridge between the currently composed document editor and platform lifecycle.
 *
 * Compose owns the rich editor state, while [DocumentWorkspaceFeature] owns the persistable tab
 * state. Android's `Activity.onStop` cannot wait for a later composition/disposal callback, so the
 * active editor registers an action that first publishes its latest visual/source value back to
 * the feature. Registrations are owner-safe: disposing an old editor after a new one has attached
 * must not clear the new editor's action.
 */
class DocumentDraftLifecycleBridge {
    internal class Registration internal constructor(internal val id: Long)

    private data class Entry(
        val registration: Registration,
        val captureAndPublish: () -> Unit,
    )

    private var nextRegistrationId = 0L
    private var active: Entry? = null

    internal fun register(captureAndPublish: () -> Unit): Registration {
        val registration = Registration(++nextRegistrationId)
        active = Entry(registration, captureAndPublish)
        return registration
    }

    internal fun unregister(registration: Registration) {
        if (active?.registration === registration) active = null
    }

    /** Returns false only when an attached editor failed to publish; no editor is a valid state. */
    internal fun captureLatest(): Boolean {
        val entry = active ?: return true
        return runCatching(entry.captureAndPublish).isSuccess
    }
}

/** Keeps the lifecycle ordering explicit and unit-testable without an Android Activity. */
internal fun captureDocumentDraftThenFlush(
    bridge: DocumentDraftLifecycleBridge,
    flush: () -> Boolean,
): Boolean {
    val captured = bridge.captureLatest()
    val flushed = flush()
    return captured && flushed
}

private object NoOpDocumentDraftPersistence : DocumentDraftPersistence {
    override fun read(uid: String): String? = null
    override fun write(uid: String, payload: () -> String): Boolean = true
    override fun delete(uid: String): Boolean = true
    override fun clearAll(): Boolean = true
}

/**
 * A uid-scoped continuation store for unsaved document tabs.
 *
 * The in-memory snapshot is the hot copy used while a session is alive. Android additionally
 * injects an AtomicFile-backed [DocumentDraftPersistence], so a process restart can restore the
 * same state without putting a large Markdown body in SavedState/Bundle. The snapshot itself
 * contains no Compose or platform objects.
 */
class DocumentDraftStore(
    private val persistence: DocumentDraftPersistence = NoOpDocumentDraftPersistence,
) {
    private var ownerUid: String? = null
    private var snapshot: DocumentWorkspaceDraftSnapshot? = null

    internal fun restore(uid: String): DocumentWorkspaceDraftSnapshot? {
        snapshot?.takeIf { ownerUid == uid }?.let { return it }

        val payload = safely { persistence.read(uid) } ?: return null
        val restored = decodeSnapshot(payload)?.normalized()
        ownerUid = uid
        snapshot = restored
        if (restored == null) safely { persistence.delete(uid) }
        return restored
    }

    internal fun save(
        uid: String,
        tabs: List<DocumentTabState>,
        activeTabId: String?,
        selectedSpaceId: String?,
        selectedFolderId: String?,
    ) {
        val draftTabs = tabs
            .asSequence()
            .filter { it.dirty || it.creating }
            .distinctBy { it.instanceId }
            .distinctBy { it.tabId }
            .toList()

        if (ownerUid != uid) {
            ownerUid = uid
            snapshot = null
        }
        if (draftTabs.isEmpty()) {
            snapshot = null
            safely { persistence.delete(uid) }
            return
        }

        val activeInstanceId = draftTabs
            .firstOrNull { it.tabId == activeTabId }
            ?.instanceId
        snapshot = DocumentWorkspaceDraftSnapshot(
            tabs = draftTabs,
            activeTabInstanceId = activeInstanceId,
            selectedSpaceId = selectedSpaceId,
            selectedFolderId = selectedFolderId,
        )
        val persistedSnapshot = requireNotNull(snapshot)
        safely { persistence.write(uid) { encodeSnapshot(persistedSnapshot) } }
    }

    internal fun clear(uid: String? = null) {
        if (uid == null) {
            safely { persistence.clearAll() }
            ownerUid = null
            snapshot = null
            return
        }
        safely { persistence.delete(uid) }
        if (ownerUid == uid) {
            ownerUid = null
            snapshot = null
        }
    }

    /** Waits for platform storage to finish its latest coalesced atomic write. */
    fun flush(): Boolean = safely { persistence.flush() } == true

    private fun encodeSnapshot(value: DocumentWorkspaceDraftSnapshot): String =
        payloadJson.encodeToString(PersistedDocumentWorkspaceDraft.from(value))

    private fun decodeSnapshot(payload: String): DocumentWorkspaceDraftSnapshot? = safely {
        val persisted = payloadJson.decodeFromString<PersistedDocumentWorkspaceDraft>(payload)
        if (persisted.schemaVersion != PERSISTED_SCHEMA_VERSION) return@safely null
        persisted.toSnapshot()
    }

    private inline fun <T> safely(action: () -> T): T? = try {
        action()
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PERSISTED_SCHEMA_VERSION = 1
        val payloadJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/** Immutable, platform-free state sufficient to resume every unsaved document instance. */
internal data class DocumentWorkspaceDraftSnapshot(
    val tabs: List<DocumentTabState>,
    val activeTabInstanceId: Long?,
    val selectedSpaceId: String?,
    val selectedFolderId: String?,
)

/** Re-validates a restored snapshot before it becomes live feature state. */
internal fun DocumentWorkspaceDraftSnapshot.normalized(): DocumentWorkspaceDraftSnapshot? {
    val normalizedTabs = tabs
        .asSequence()
        .filter { it.dirty || it.creating }
        .filter { it.tabId.isNotBlank() && it.spaceId.isNotBlank() }
        .filter { tab ->
            if (tab.creating) tab.documentId == null && tab.revision == null
            else tab.documentId != null && tab.revision != null
        }
        .distinctBy { it.instanceId }
        .distinctBy { it.tabId }
        .toList()
    if (normalizedTabs.isEmpty()) return null

    val activeInstanceId = activeTabInstanceId
        ?.takeIf { instanceId -> normalizedTabs.any { it.instanceId == instanceId } }
    return copy(
        tabs = normalizedTabs,
        activeTabInstanceId = activeInstanceId,
        selectedSpaceId = selectedSpaceId?.takeIf(String::isNotBlank),
    )
}

/**
 * Reopening an already restored dirty tab may verify ACL/path remotely, but remote content must
 * never replace its local title or Markdown draft.
 */
internal fun refreshRestoredDocumentPath(
    existing: DocumentTabState,
    verified: com.virjar.tk.model.Document,
): DocumentTabState? {
    if (existing.documentId != verified.documentId || existing.spaceId != verified.spaceId) return null
    return existing.copy(
        parentId = verified.parentId,
        ancestorIds = verified.ancestorIds,
    )
}

@Serializable
private data class PersistedDocumentWorkspaceDraft(
    val schemaVersion: Int = 1,
    val tabs: List<PersistedDocumentTabDraft>,
    val activeTabInstanceId: Long? = null,
    val selectedSpaceId: String? = null,
    val selectedFolderId: String? = null,
) {
    fun toSnapshot() = DocumentWorkspaceDraftSnapshot(
        tabs = tabs.map(PersistedDocumentTabDraft::toTab),
        activeTabInstanceId = activeTabInstanceId,
        selectedSpaceId = selectedSpaceId,
        selectedFolderId = selectedFolderId,
    )

    companion object {
        fun from(snapshot: DocumentWorkspaceDraftSnapshot) = PersistedDocumentWorkspaceDraft(
            tabs = snapshot.tabs.map(PersistedDocumentTabDraft::from),
            activeTabInstanceId = snapshot.activeTabInstanceId,
            selectedSpaceId = snapshot.selectedSpaceId,
            selectedFolderId = snapshot.selectedFolderId,
        )
    }
}

@Serializable
private data class PersistedDocumentTabDraft(
    val tabId: String,
    val instanceId: Long,
    val documentId: String? = null,
    val spaceId: String,
    val parentId: String? = null,
    val ancestorIds: List<String> = emptyList(),
    val savedTitle: String,
    val savedMarkdown: String,
    val draftTitle: String,
    val draftMarkdown: String,
    val revision: Long? = null,
    val dirty: Boolean,
    val creating: Boolean,
    val editGeneration: Long,
) {
    fun toTab() = DocumentTabState(
        tabId = tabId,
        instanceId = instanceId,
        documentId = documentId,
        spaceId = spaceId,
        parentId = parentId,
        ancestorIds = ancestorIds,
        savedTitle = savedTitle,
        savedMarkdown = savedMarkdown,
        draftTitle = draftTitle,
        draftMarkdown = draftMarkdown,
        revision = revision,
        dirty = dirty,
        creating = creating,
        editGeneration = editGeneration,
    )

    companion object {
        fun from(tab: DocumentTabState) = PersistedDocumentTabDraft(
            tabId = tab.tabId,
            instanceId = tab.instanceId,
            documentId = tab.documentId,
            spaceId = tab.spaceId,
            parentId = tab.parentId,
            ancestorIds = tab.ancestorIds,
            savedTitle = tab.savedTitle,
            savedMarkdown = tab.savedMarkdown,
            draftTitle = tab.draftTitle,
            draftMarkdown = tab.draftMarkdown,
            revision = tab.revision,
            dirty = tab.dirty,
            creating = tab.creating,
            editGeneration = tab.editGeneration,
        )
    }
}
