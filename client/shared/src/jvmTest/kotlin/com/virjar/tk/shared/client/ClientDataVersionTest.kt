package com.virjar.tk.shared.client

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientDataVersionTest {
    @Test
    fun `zero baseline adopts existing data while later major clears only owned data`() {
        val directory = createTempDirectory("client-version-").toFile()
        val outside = createTempDirectory("client-outside-").toFile()
        try {
            val privateData = JvmPrivateDataDirectory.openExisting(directory)
            privateData.atomicTextFile(fileName = "credentials").replaceText("retained-account")
            privateData.atomicTextFile(fileName = ".teamtalk-desktop-data").replaceText("owner")
            JvmClientDataLease.acquire(directory).use {
                assertFalse(prepareJvmClientDataVersion(directory, currentMajor = 0))
                assertEquals("retained-account", directory.resolve("credentials").readText())
                assertFalse(prepareJvmClientDataVersion(directory, currentMajor = 0))
                outside.resolve("keep").writeText("outside")
                Files.createSymbolicLink(directory.resolve("external-link").toPath(), outside.toPath())
                assertTrue(prepareJvmClientDataVersion(directory, currentMajor = 1))
                assertFalse(directory.resolve("credentials").exists())
                assertTrue(directory.resolve(".teamtalk-desktop-data").exists())
                assertTrue(directory.resolve(".lock").exists())
                assertEquals("outside", outside.resolve("keep").readText())
                assertEquals("ready:1", directory.resolve(".client-data-version").readText())
                assertFailsWith<IllegalStateException> { prepareJvmClientDataVersion(directory, currentMajor = 0) }
            }
        } finally {
            directory.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `interrupted major reset resumes and cannot be mistaken for a usable old installation`() {
        var marker: String? = "ready:0"
        assertFailsWith<IllegalStateException> {
            prepareClientDataVersion(1, { marker }, { marker = it }) { error("interrupted") }
        }
        assertEquals("reset:1", marker)
        assertFailsWith<IllegalStateException> {
            prepareClientDataVersion(0, { marker }, { marker = it }) { error("must not reset on downgrade") }
        }
        var resumed = false
        assertTrue(prepareClientDataVersion(1, { marker }, { marker = it }) { resumed = true })
        assertTrue(resumed)
        assertEquals("ready:1", marker)
    }

    @Test
    fun `second process cannot acquire a data root while the first client owns it`() {
        val directory = createTempDirectory("client-lease-").toFile()
        try {
            JvmClientDataLease.acquire(directory).use {
                assertFailsWith<java.nio.channels.OverlappingFileLockException> {
                    JvmClientDataLease.acquire(directory)
                }
            }
            JvmClientDataLease.acquire(directory).close()
        } finally { directory.deleteRecursively() }
    }
}
