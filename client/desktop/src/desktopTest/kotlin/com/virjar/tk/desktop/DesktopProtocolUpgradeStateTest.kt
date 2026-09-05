package com.virjar.tk.desktop

import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopProtocolUpgradeStateTest {
    @Test
    fun `new zero refusal survives restart without inheriting the legacy zero refusal`() {
        val directory = Files.createTempDirectory("teamtalk-zero-version-fence").toFile()
        try {
            val deployment = DeploymentIdentity.from("127.0.0.1", 5100, "http://127.0.0.1:8080")
            JvmPrivateDataDirectory.openExisting(directory).atomicTextFile(fileName = "auth.properties")
                .replaceText(
                    """
                    owner_generation=1
                    deployment_fingerprint=${deployment.fingerprint}
                    uid=user-a
                    refresh_token=refresh-a
                    dataset_id=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa
                    rejected_protocol_versions=0
                    """.trimIndent(),
                )
            val store = DesktopTokenStore(directory, deployment)
            val migrated = store.claimOwner()
            assertNotNull(migrated.savedLogin)
            assertTrue(migrated.rejectedProtocolVersions.isEmpty())

            assertTrue(store.markProtocolVersionRejected(ProtocolVersion(0, 0).id))
            val restarted = DesktopTokenStore(directory, deployment).claimOwner()
            assertEquals(setOf(0), restarted.rejectedProtocolVersions)
            val login = assertNotNull(restarted.savedLogin)
            assertEquals("user-a", login.uid)
            assertEquals("refresh-a", login.refreshToken)
            assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", login.datasetId)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy refusal does not reject new zero version and precise minor fences preserve login`() {
        val directory = Files.createTempDirectory("teamtalk-version-fence").toFile()
        try {
            val deployment = DeploymentIdentity.from("127.0.0.1", 5100, "http://127.0.0.1:8080")
            JvmPrivateDataDirectory.openExisting(directory).atomicTextFile(fileName = "auth.properties")
                .replaceText(
                    """
                    owner_generation=1
                    deployment_fingerprint=${deployment.fingerprint}
                    uid=user-a
                    refresh_token=refresh-a
                    dataset_id=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa
                    rejected_protocol_versions=0
                    """.trimIndent(),
                )
            val store = DesktopTokenStore(directory, deployment)
            val initial = store.claimOwner()
            assertNotNull(initial.savedLogin)
            assertTrue(initial.rejectedProtocolVersions.isEmpty())

            val rejected = ProtocolVersion(1, 7).id
            assertTrue(store.markProtocolVersionRejected(rejected))
            val reopened = DesktopTokenStore(directory, deployment).claimOwner()
            assertNotNull(reopened.savedLogin)
            assertEquals(setOf(rejected), reopened.rejectedProtocolVersions)
            assertTrue(ProtocolVersion(1, 8).id !in reopened.rejectedProtocolVersions)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `known refusal takes priority over an otherwise active offline workspace`() {
        assertEquals(
            DesktopAuthenticationSurface.PROTOCOL_UPGRADE,
            desktopAuthenticationSurface(true, true, requiresProtocolUpgrade = true),
        )
    }
}
