package com.virjar.tk.navigation.feature

import com.virjar.tk.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentDraftStoreTest {

    @Test
    fun `lifecycle capture uses newest editor and stale disposal cannot detach it`() {
        val bridge = DocumentDraftLifecycleBridge()
        val captured = mutableListOf<String>()
        val oldRegistration = bridge.register { captured += "old" }
        val newRegistration = bridge.register { captured += "new" }

        bridge.unregister(oldRegistration)

        assertTrue(bridge.captureLatest())
        assertEquals(listOf("new"), captured)
        bridge.unregister(newRegistration)
        assertTrue(bridge.captureLatest())
        assertEquals(listOf("new"), captured)
    }

    @Test
    fun `lifecycle path captures before flush and still flushes after editor failure`() {
        val bridge = DocumentDraftLifecycleBridge()
        val events = mutableListOf<String>()
        bridge.register {
            events += "capture"
            error("editor unavailable")
        }

        assertFalse(
            captureDocumentDraftThenFlush(bridge) {
                events += "flush"
                true
            }
        )
        assertEquals(listOf("capture", "flush"), events)
    }

    @Test
    fun `Activity recreation snapshot restores full dirty and creating identities`() {
        val persistence = FakeDocumentDraftPersistence()
        val store = DocumentDraftStore(persistence)
        val dirty = existingTab(
            tabId = "doc-a",
            instanceId = 41,
            markdown = "# 本地草稿\n" + "内容".repeat(20_000),
        )
        val creating = DocumentTabState(
            tabId = "draft-2-123",
            instanceId = 42,
            documentId = null,
            spaceId = "space-a",
            parentId = "folder-new",
            ancestorIds = listOf("root", "folder-new"),
            savedTitle = "",
            savedMarkdown = "",
            draftTitle = "新建中的文档",
            draftMarkdown = "尚未保存",
            revision = null,
            dirty = true,
            creating = true,
            editGeneration = 7,
        )
        val clean = existingTab("doc-clean", 43, "远端正文").copy(dirty = false)

        store.save(
            uid = "user-a",
            tabs = listOf(clean, dirty, creating),
            activeTabId = creating.tabId,
            selectedSpaceId = "space-a",
            selectedFolderId = "folder-new",
        )

        // A brand-new store models a fresh Android process reading the AtomicFile payload.
        val restored = requireNotNull(
            DocumentDraftStore(persistence).restore("user-a")?.normalized(),
        )
        assertEquals(listOf(41L, 42L), restored.tabs.map { it.instanceId })
        assertEquals(42L, restored.activeTabInstanceId)
        assertEquals("space-a", restored.selectedSpaceId)
        assertEquals("folder-new", restored.selectedFolderId)
        assertEquals(dirty, restored.tabs.first())
        assertEquals(creating, restored.tabs.last())
    }

    @Test
    fun `draft store never crosses signed in uid and logout clears it`() {
        val persistence = FakeDocumentDraftPersistence()
        val store = DocumentDraftStore(persistence)
        store.save("user-a", listOf(existingTab("doc-a", 1, "A")), "doc-a", "space-a", null)

        assertNull(store.restore("user-b"))
        assertEquals("doc-a", store.restore("user-a")?.tabs?.single()?.documentId)

        store.clear("user-b")
        assertEquals("doc-a", store.restore("user-a")?.tabs?.single()?.documentId)
        store.clear("user-a")
        assertNull(store.restore("user-a"))
        assertNull(persistence.payloads["user-a"])
    }

    @Test
    fun `successful save or explicit discard removes retained body`() {
        val persistence = FakeDocumentDraftPersistence()
        val store = DocumentDraftStore(persistence)
        val dirty = existingTab("doc-a", 1, "未保存")
        store.save("user-a", listOf(dirty), dirty.tabId, dirty.spaceId, dirty.parentId)
        assertTrue(store.restore("user-a") != null)

        store.save(
            uid = "user-a",
            tabs = listOf(dirty.copy(dirty = false, savedMarkdown = "未保存")),
            activeTabId = dirty.tabId,
            selectedSpaceId = dirty.spaceId,
            selectedFolderId = dirty.parentId,
        )
        assertNull(store.restore("user-a"))
        assertNull(persistence.payloads["user-a"])

        store.save("user-a", listOf(dirty), dirty.tabId, dirty.spaceId, dirty.parentId)
        store.save("user-a", emptyList(), null, null, null)
        assertNull(store.restore("user-a"))
    }

    @Test
    fun `corrupt local payload is deleted and never blocks document entry`() {
        val persistence = FakeDocumentDraftPersistence().apply {
            payloads["user-a"] = "{not valid json"
        }

        assertNull(DocumentDraftStore(persistence).restore("user-a"))
        assertNull(persistence.payloads["user-a"])
        assertEquals(listOf("user-a"), persistence.deletedUids)
    }

    @Test
    fun `remote verification refreshes path without overwriting restored local content`() {
        val restored = existingTab("doc-a", 12, "本地尚未保存的正文").copy(
            savedTitle = "旧基线",
            savedMarkdown = "旧远端正文",
            draftTitle = "本地新标题",
            parentId = "old-parent",
            ancestorIds = listOf("old-root"),
            editGeneration = 9,
        )
        val remote = document(
            id = "doc-a",
            title = "另一设备已保存的标题",
            markdown = "另一设备正文",
            parentId = "new-parent",
            ancestorIds = listOf("new-root", "new-parent"),
        )

        val refreshed = requireNotNull(refreshRestoredDocumentPath(restored, remote))
        assertEquals("new-parent", refreshed.parentId)
        assertEquals(listOf("new-root", "new-parent"), refreshed.ancestorIds)
        assertEquals("旧基线", refreshed.savedTitle)
        assertEquals("旧远端正文", refreshed.savedMarkdown)
        assertEquals("本地新标题", refreshed.draftTitle)
        assertEquals("本地尚未保存的正文", refreshed.draftMarkdown)
        assertEquals(9L, refreshed.editGeneration)
        assertTrue(refreshed.dirty)
        assertFalse(refreshed.creating)
    }

    @Test
    fun `invalid restored creation identity is rejected defensively`() {
        val invalid = existingTab("doc-a", 1, "正文").copy(
            creating = true,
            documentId = "doc-a",
            revision = 1,
        )
        val snapshot = DocumentWorkspaceDraftSnapshot(
            tabs = listOf(invalid),
            activeTabInstanceId = invalid.instanceId,
            selectedSpaceId = invalid.spaceId,
            selectedFolderId = null,
        )

        assertNull(snapshot.normalized())
    }

    private fun existingTab(tabId: String, instanceId: Long, markdown: String) = DocumentTabState(
        tabId = tabId,
        instanceId = instanceId,
        documentId = tabId,
        spaceId = "space-a",
        parentId = "folder-a",
        ancestorIds = listOf("root", "folder-a"),
        savedTitle = "原标题",
        savedMarkdown = "原正文",
        draftTitle = "草稿标题",
        draftMarkdown = markdown,
        revision = 3,
        dirty = true,
        creating = false,
        editGeneration = 4,
    )

    private fun document(
        id: String,
        title: String,
        markdown: String,
        parentId: String?,
        ancestorIds: List<String>,
    ) = Document(
        documentId = id,
        spaceId = "space-a",
        parentId = parentId,
        title = title,
        markdown = markdown,
        revision = 4,
        createdBy = "u1",
        createdAt = 1,
        updatedBy = "u2",
        updatedAt = 2,
        ancestorIds = ancestorIds,
    )

    private class FakeDocumentDraftPersistence : DocumentDraftPersistence {
        val payloads = mutableMapOf<String, String>()
        val deletedUids = mutableListOf<String>()

        override fun read(uid: String): String? = payloads[uid]

        override fun write(uid: String, payload: () -> String): Boolean {
            payloads[uid] = payload()
            return true
        }

        override fun delete(uid: String): Boolean {
            deletedUids += uid
            payloads.remove(uid)
            return true
        }

        override fun clearAll(): Boolean {
            payloads.clear()
            return true
        }
    }
}
