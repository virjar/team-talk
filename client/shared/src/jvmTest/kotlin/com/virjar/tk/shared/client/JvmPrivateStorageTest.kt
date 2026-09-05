package com.virjar.tk.shared.client

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmPrivateStorageTest {
    @Test
    fun `capability selection prefers POSIX and otherwise requires Windows ACL`() {
        assertEquals(JvmPrivateAccessKind.POSIX, selectJvmPrivateAccessKind(true, true))
        assertEquals(JvmPrivateAccessKind.WINDOWS_ACL, selectJvmPrivateAccessKind(false, true))
        assertFailsWith<IllegalStateException> { selectJvmPrivateAccessKind(false, false) }
    }

    @Test
    fun `Windows private ACL accepts exactly one full owner entry`() {
        val owner = Principal("owner")
        val other = Principal("other")
        val expectedFile = WindowsOwnerOnlyAclPolicy.ownerOnlyAcl(owner, directory = false)
        val expectedDirectory = WindowsOwnerOnlyAclPolicy.ownerOnlyAcl(owner, directory = true)

        assertTrue(WindowsOwnerOnlyAclPolicy.isOwnerOnly(owner, expectedFile, directory = false))
        assertTrue(WindowsOwnerOnlyAclPolicy.isOwnerOnly(owner, expectedDirectory, directory = true))
        assertFalse(WindowsOwnerOnlyAclPolicy.isOwnerOnly(owner, expectedDirectory, directory = false))
        assertFalse(WindowsOwnerOnlyAclPolicy.isOwnerOnly(other, expectedFile, directory = false))
        assertFalse(
            WindowsOwnerOnlyAclPolicy.isOwnerOnly(
                owner,
                expectedFile + allow(other, setOf(AclEntryPermission.READ_DATA)),
                directory = false,
            ),
        )
        assertFalse(
            WindowsOwnerOnlyAclPolicy.isOwnerOnly(
                owner,
                listOf(allow(owner, setOf(AclEntryPermission.READ_DATA))),
                directory = false,
            ),
        )
    }

    @Test
    fun `POSIX text store creates exact modes and rejects a hard linked payload`() {
        val root = Files.createTempDirectory("teamtalk-private-store-")
        if (Files.getFileAttributeView(root, PosixFileAttributeView::class.java) == null) {
            root.toFile().deleteRecursively()
            return
        }
        try {
            val data = JvmPrivateDataDirectory.openExisting(root.toFile())
            val store = data.atomicTextFile(listOf("auth"), "credential")
            store.replaceText("secret")

            val directory = root.resolve("auth")
            val payload = directory.resolve("credential")
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS),
            )
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(payload, LinkOption.NOFOLLOW_LINKS),
            )

            Files.createLink(root.resolve("credential-link"), payload)
            assertFailsWith<IllegalArgumentException> { store.readText() }
            assertFailsWith<IllegalArgumentException> { data.validatePrivateTree() }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `owner anchor chain rejects writable and symbolic link parents`() {
        val anchor = Files.createTempDirectory("teamtalk-private-chain-")
            .toRealPath(LinkOption.NOFOLLOW_LINKS)
        if (Files.getFileAttributeView(anchor, PosixFileAttributeView::class.java) == null) {
            anchor.toFile().deleteRecursively()
            return
        }
        try {
            val writable = Files.createDirectory(anchor.resolve("writable"))
            val writableChild = Files.createDirectory(writable.resolve("child"))
            Files.setPosixFilePermissions(writable, PosixFilePermissions.fromString("rwxrwx---"))
            assertFailsWith<IllegalArgumentException> {
                JvmPrivateDataDirectory.openOrCreate(writableChild.resolve("data").toFile(), anchor.toFile())
            }
            assertFalse(Files.exists(writableChild.resolve("data"), LinkOption.NOFOLLOW_LINKS))

            val real = Files.createDirectory(anchor.resolve("real"))
            Files.createDirectory(real.resolve("nested"))
            val alias = Files.createSymbolicLink(anchor.resolve("alias"), real)
            assertFailsWith<IllegalArgumentException> {
                JvmPrivateDataDirectory.openOrCreate(alias.resolve("nested/data").toFile(), anchor.toFile())
            }
            assertFalse(Files.exists(real.resolve("nested/data"), LinkOption.NOFOLLOW_LINKS))
        } finally {
            anchor.toFile().deleteRecursively()
        }
    }

    @Test
    fun `macOS extended ACL rejects allow accepts deny and clears safe inheritance`() {
        if (!JvmMacOsAcl.isMacOs()) return
        val anchor = Files.createTempDirectory("teamtalk-macos-acl-").toRealPath()
        try {
            runMacChmod("+a", "everyone deny delete", anchor)
            JvmPrivateDataDirectory.openExisting(anchor.toFile())

            runMacChmod("-N", anchor)
            runMacChmod("+a", "everyone allow read", anchor)
            assertFailsWith<IllegalArgumentException> {
                JvmPrivateDataDirectory.openExisting(anchor.toFile())
            }

            runMacChmod("-N", anchor)
            runMacChmod("+a", "everyone allow read,file_inherit,directory_inherit", anchor)
            val data = JvmPrivateDataDirectory.openOrCreate(anchor.resolve("data").toFile(), anchor.toFile())
            JvmPrivateDataDirectory.openExisting(data.root.toFile())

            val store = data.atomicTextFile(fileName = "credential")
            store.replaceText("secret")
            val payload = data.root.resolve("credential")
            runMacChmod("+a", "everyone allow read", payload)
            assertFailsWith<IllegalArgumentException> { store.readText() }
        } finally {
            runCatching { runMacChmod("-N", anchor.resolve("data/credential")) }
            runCatching { runMacChmod("-N", anchor.resolve("data")) }
            runCatching { runMacChmod("-N", anchor) }
            anchor.toFile().deleteRecursively()
        }
    }

    private fun runMacChmod(vararg arguments: Any) {
        val command = listOf("/bin/chmod") + arguments.map(Any::toString)
        val process = ProcessBuilder(command).apply { environment()["LC_ALL"] = "C" }.start()
        check(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
            "macOS chmod test setup failed"
        }
    }

    private fun allow(principal: UserPrincipal, permissions: Set<AclEntryPermission>): AclEntry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(permissions)
            .build()

    private data class Principal(private val value: String) : UserPrincipal {
        override fun getName(): String = value
    }
}
