package com.virjar.tk.navigation.feature

import com.virjar.tk.ui.screen.canManageGroupMember
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupRequestIsolationTest {

    @Test
    fun `late group A detail cannot overwrite group B`() = runTest {
        val gate = GroupRequestGate<String>()
        val responseA = CompletableDeferred<String>()
        val responseB = CompletableDeferred<String>()
        var committed: String? = null

        val tokenA = gate.begin("chat-a")
        val jobA = launch(start = CoroutineStart.UNDISPATCHED) {
            val value = responseA.await()
            if (gate.isCurrent(tokenA)) committed = value
        }
        val tokenB = gate.begin("chat-b")
        val jobB = launch(start = CoroutineStart.UNDISPATCHED) {
            val value = responseB.await()
            if (gate.isCurrent(tokenB)) committed = value
        }

        responseB.complete("B")
        jobB.join()
        responseA.complete("A")
        jobA.join()

        assertEquals("B", committed)
        assertTrue(gate.targets("chat-b"))
        assertFalse(gate.targets("chat-a"))
    }

    @Test
    fun `late folder response cannot overwrite current root or finish its loading`() = runTest {
        val gate = GroupRequestGate<GroupFileLocation>()
        val folderResponse = CompletableDeferred<String>()
        val rootResponse = CompletableDeferred<String>()
        var entries: String? = null
        var loading = false

        val folderToken = gate.begin(GroupFileLocation("chat-a", "folder-1"))
        loading = true
        val folderJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val value = folderResponse.await()
            if (gate.isCurrent(folderToken)) entries = value
            if (gate.isCurrent(folderToken)) loading = false
        }
        val rootToken = gate.begin(GroupFileLocation("chat-a", null))
        loading = true
        val rootJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val value = rootResponse.await()
            if (gate.isCurrent(rootToken)) entries = value
            if (gate.isCurrent(rootToken)) loading = false
        }

        folderResponse.complete("folder")
        folderJob.join()
        assertTrue(loading)
        rootResponse.complete("root")
        rootJob.join()

        assertEquals("root", entries)
        assertFalse(loading)
    }

    @Test
    fun `member management is decided only by current actor hierarchy`() {
        assertFalse(canManageGroupMember(actorRole = 0, targetRole = 1, isSelf = false))
        assertFalse(canManageGroupMember(actorRole = 0, targetRole = 0, isSelf = false))
        assertTrue(canManageGroupMember(actorRole = 1, targetRole = 0, isSelf = false))
        assertFalse(canManageGroupMember(actorRole = 1, targetRole = 1, isSelf = false))
        assertFalse(canManageGroupMember(actorRole = 1, targetRole = 2, isSelf = false))
        assertTrue(canManageGroupMember(actorRole = 2, targetRole = 1, isSelf = false))
        assertFalse(canManageGroupMember(actorRole = 2, targetRole = 2, isSelf = false))
        assertFalse(canManageGroupMember(actorRole = 2, targetRole = 0, isSelf = true))
    }
}
