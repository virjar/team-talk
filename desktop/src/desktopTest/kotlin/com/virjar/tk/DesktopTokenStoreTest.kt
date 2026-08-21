package com.virjar.tk

import com.virjar.tk.client.DeploymentIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTokenStoreTest {
    private val deploymentIdentity = DeploymentIdentity.from(
        tcpHost = "im.test.example",
        tcpPort = 5100,
        serverUrl = "https://files.test.example/api",
    )

    @Test
    fun `new owner survives stale save clear and store reopen`() {
        val directory = Files.createTempDirectory("teamtalk-auth-owner").toFile()
        try {
            val firstStore = DesktopTokenStore(directory, deploymentIdentity)
            val firstOwner = firstStore.claimOwner()
            if (Files.getFileAttributeView(
                    directory.toPath(),
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ) != null
            ) {
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(directory.toPath().resolve("auth.properties")),
                )
            }
            val firstLogin = requireNotNull(
                firstStore.save(firstOwner.generation, "user-a", "refresh-a"),
            )

            val secondStore = DesktopTokenStore(directory, deploymentIdentity)
            val secondOwner = secondStore.claimOwner()
            assertEquals(
                firstLogin.copy(ownerGeneration = secondOwner.generation),
                secondOwner.savedLogin,
            )
            val secondLogin = requireNotNull(
                secondStore.save(secondOwner.generation, "user-a", "refresh-b"),
            )

            assertNull(
                firstStore.save(firstOwner.generation, "user-a", "late-refresh-a"),
                "stale owner must not overwrite the rotated credential",
            )
            assertFalse(firstStore.compareAndClear(firstLogin))

            val reopenedStore = DesktopTokenStore(directory, deploymentIdentity)
            val reopenedOwner = reopenedStore.claimOwner()
            assertEquals(
                secondLogin.copy(ownerGeneration = reopenedOwner.generation),
                reopenedOwner.savedLogin,
            )
            assertFalse(secondStore.compareAndClear(secondLogin))
            assertTrue(reopenedStore.compareAndClear(requireNotNull(reopenedOwner.savedLogin)))

            val afterClear = DesktopTokenStore(directory, deploymentIdentity).claimOwner()
            assertNull(afterClear.savedLogin)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `deployment change invalidates persisted login before auto login`() {
        val directory = Files.createTempDirectory("teamtalk-auth-deployment").toFile()
        try {
            val firstStore = DesktopTokenStore(directory, deploymentIdentity)
            val firstOwner = firstStore.claimOwner()
            requireNotNull(firstStore.save(firstOwner.generation, "user-a", "refresh-for-a"))
            val deploymentB = DeploymentIdentity.from(
                tcpHost = deploymentIdentity.tcpHost,
                tcpPort = deploymentIdentity.tcpPort,
                serverUrl = "https://other-files.test.example/api",
            )

            val switched = DesktopTokenStore(directory, deploymentB).claimOwner()

            assertNull(switched.savedLogin)
            assertTrue("refresh-for-a" !in directory.resolve("auth.properties").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy token format without deployment identity is invalidated`() {
        val directory = Files.createTempDirectory("teamtalk-auth-legacy-deployment").toFile()
        try {
            val store = DesktopTokenStore(directory, deploymentIdentity)
            val owner = store.claimOwner()
            requireNotNull(store.save(owner.generation, "user-a", "legacy-refresh"))
            val authFile = directory.resolve("auth.properties")
            val properties = Properties().apply {
                authFile.reader().use { reader -> load(reader) }
                remove("deployment_fingerprint")
            }
            authFile.writer().use { writer -> properties.store(writer, "legacy") }

            val claimed = DesktopTokenStore(directory, deploymentIdentity).claimOwner()

            assertNull(claimed.savedLogin)
            assertTrue("legacy-refresh" !in authFile.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `installation identity is atomic private and rejects a linked replacement`() {
        val directory = Files.createTempDirectory("teamtalk-device-owner").toFile()
        try {
            val first = desktopInstallationDeviceId(directory)
            assertEquals(first, desktopInstallationDeviceId(directory))
            val identity = directory.toPath().resolve("device-id")
            if (Files.getFileAttributeView(
                    identity,
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ) != null
            ) {
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(identity),
                )
                val victim = directory.toPath().resolve("victim")
                Files.delete(identity)
                Files.writeString(victim, "unchanged")
                Files.createSymbolicLink(identity, victim)

                assertFailsWith<IllegalArgumentException> { desktopInstallationDeviceId(directory) }
                assertEquals("unchanged", Files.readString(victim))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
