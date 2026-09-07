package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.desktop.media.desktopMediaNamespace
import com.virjar.tk.shared.client.AccountDataOwner
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAccountDataCleanupTest {
    @Test
    fun `banned owner cleanup removes database media and durable drafts without touching siblings or installation`() {
        val directory = createTempDirectory("desktop-account-cleanup-").toFile()
        try {
            val owner = AccountDataOwner("a".repeat(64), "00000000-0000-4000-8000-000000000001", "user1")
            val users = "deployments/${owner.deploymentFingerprint}/datasets/${owner.datasetId}/users"
            val draftNamespace = desktopDocumentDraftOwnerNamespace(DocumentDraftOwnerKey(
                owner.deploymentFingerprint, owner.datasetId, owner.uid,
            ))
            val accountDirectories = listOf(
                "$users/${owner.uid}", "$users/${owner.uid}.corrupt-1234",
                "media_e2/${desktopMediaNamespace(owner.deploymentFingerprint, owner.datasetId, owner.uid)}",
                "document-drafts/v3/deployments/${owner.deploymentFingerprint}/owners/$draftNamespace",
            )
            accountDirectories.forEach { path ->
                directory.resolve(path).apply { mkdirs() }.resolve("private-data").writeText("owned")
            }
            val other = directory.resolve("$users/user10").apply { mkdirs() }.resolve("private-data")
            other.writeText("unrelated")
            directory.resolve(".teamtalk-desktop-data").writeText("installation")
            val cleanup = desktopAccountDataCleanup(directory)
            cleanup.begin(owner)
            cleanup.deleteOwnedData(owner)
            accountDirectories.forEach { assertFalse(directory.resolve(it).exists(), it) }
            assertEquals("unrelated", other.readText())
            assertEquals("installation", directory.resolve(".teamtalk-desktop-data").readText())
            assertEquals(listOf(owner), cleanup.pendingOwners())
            cleanup.complete(owner)
            assertTrue(cleanup.pendingOwners().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
