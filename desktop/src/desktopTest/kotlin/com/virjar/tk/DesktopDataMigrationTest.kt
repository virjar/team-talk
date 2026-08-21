package com.virjar.tk.env

import com.virjar.tk.client.JvmPrivateDataDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDataMigrationTest {
    @Test
    fun `private legacy tree is copied once and remains byte-for-byte in place`() {
        withPosixHome { home ->
            val plan = plan(home)
            val legacy = createLegacy(plan, includeLock = true)
            val before = legacySnapshot(legacy)

            val first = DesktopDataMigration.prepare(plan)

            assertEquals(plan.dataDirectory.canonicalFile, first.dataDirectory.canonicalFile)
            assertEquals(legacy.toFile().canonicalFile, first.legacySourceDirectory?.canonicalFile)
            assertTrue(Files.exists(legacy, LinkOption.NOFOLLOW_LINKS))
            assertEquals(before, legacySnapshot(legacy))
            assertEquals(
                "uid=alice\nrefresh=redacted\n",
                Files.readString(plan.dataDirectory.toPath().resolve("auth.properties")),
            )

            val second = DesktopDataMigration.prepare(plan)
            assertEquals(first.dataDirectory.canonicalFile, second.dataDirectory.canonicalFile)
            assertEquals(legacy.toFile().canonicalFile, second.legacySourceDirectory?.canonicalFile)
        }
    }

    @Test
    fun `overwide legacy payload fails closed without publishing or changing source`() {
        withPosixHome { home ->
            val plan = plan(home)
            val legacy = createLegacy(plan, includeLock = true)
            val auth = legacy.resolve("auth.properties")
            Files.setPosixFilePermissions(auth, PosixFilePermissions.fromString("rw-r--r--"))
            val before = Files.readAllBytes(auth)

            assertFailsWith<IllegalStateException> { DesktopDataMigration.prepare(plan) }

            assertFalse(Files.exists(plan.dataDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertTrue(before.contentEquals(Files.readAllBytes(auth)))
            assertEquals(
                PosixFilePermissions.fromString("rw-r--r--"),
                Files.getPosixFilePermissions(auth, LinkOption.NOFOLLOW_LINKS),
            )
        }
    }

    @Test
    fun `automatic migration never creates a lock in the legacy tree`() {
        withPosixHome { home ->
            val plan = plan(home)
            val legacy = createLegacy(plan, includeLock = false)

            assertFailsWith<IllegalArgumentException> { DesktopDataMigration.prepare(plan) }

            assertFalse(Files.exists(legacy.resolve(".lock"), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(plan.dataDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `legacy lock is part of the exact private tree gate`() {
        withPosixHome { home ->
            val plan = plan(home)
            val legacy = createLegacy(plan, includeLock = true)
            val lock = legacy.resolve(".lock")
            Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r--r--"))

            assertFailsWith<IllegalArgumentException> { DesktopDataMigration.prepare(plan) }

            assertFalse(Files.exists(plan.dataDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals(
                PosixFilePermissions.fromString("rw-r--r--"),
                Files.getPosixFilePermissions(lock, LinkOption.NOFOLLOW_LINKS),
            )
        }
    }

    @Test
    fun `unknown dual roots are rejected instead of choosing an account`() {
        withPosixHome { home ->
            val plan = plan(home)
            createLegacy(plan, includeLock = true)
            DesktopDataDirectoryPolicy.prepareBaseDirectory(plan)
            val target = JvmPrivateDataDirectory.openOrCreate(plan.dataDirectory, plan.ownerAnchor)
            target.atomicTextFile(fileName = ".teamtalk-desktop-data")
                .replaceText("teamtalk-desktop-data-v1\n")
            target.atomicTextFile(fileName = "auth.properties").replaceText("uid=bob\n")

            assertFailsWith<IllegalArgumentException> { DesktopDataMigration.prepare(plan) }
        }
    }

    @Test
    fun `incomplete migration stage is validation only and remains untouched`() {
        withPosixHome { home ->
            val plan = plan(home)
            createLegacy(plan, includeLock = true)
            DesktopDataDirectoryPolicy.prepareBaseDirectory(plan)
            val target = plan.dataDirectory.toPath()
            val stage = target.resolveSibling(".${target.fileName}.migrating-v1")
            val stageData = JvmPrivateDataDirectory.createNew(stage.toFile(), plan.ownerAnchor)

            assertFailsWith<IllegalArgumentException> { DesktopDataMigration.prepare(plan) }

            assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            assertTrue(stageData.isEmpty())
        }
    }

    private fun plan(home: File): DesktopDataDirectoryPlan = DesktopDataDirectoryPlan(
        dataDirectory = home.resolve(".local/share/teamtalk"),
        currentUserAnchor = home,
        ownerAnchor = home,
        baseDirectory = home.resolve(".local/share"),
        legacyInstallationDataDirectory = home.resolve("old-install/data"),
        isExplicitOverride = false,
    )

    private fun createLegacy(plan: DesktopDataDirectoryPlan, includeLock: Boolean): java.nio.file.Path {
        val install = plan.currentUserAnchor.toPath().resolve("old-install")
        Files.createDirectory(install)
        val legacy = JvmPrivateDataDirectory.openOrCreate(
            plan.legacyInstallationDataDirectory!!,
            plan.currentUserAnchor,
        )
        legacy.atomicTextFile(fileName = "auth.properties")
            .replaceText("uid=alice\nrefresh=redacted\n")
        legacy.preparePrivateFile(listOf("users", "alice"), "cache_e2.db")
        if (includeLock) legacy.preparePrivateFile(emptyList(), ".lock")
        return legacy.root
    }

    private fun legacySnapshot(root: java.nio.file.Path): Map<String, LegacyNodeSnapshot> =
        Files.walk(root).use { paths ->
            paths.toList().associate { path ->
                val relative = root.relativize(path).toString()
                val directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                relative to LegacyNodeSnapshot(
                    directory = directory,
                    permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
                    bytes = if (directory) null else Files.readAllBytes(path).toList(),
                )
            }
        }

    private data class LegacyNodeSnapshot(
        val directory: Boolean,
        val permissions: Set<PosixFilePermission>,
        val bytes: List<Byte>?,
    )

    private inline fun withPosixHome(block: (File) -> Unit) {
        val realUserHome = File(requireNotNull(System.getProperty("user.dir")))
            .toPath()
            .toRealPath()
        val home = Files.createTempDirectory(realUserHome, "teamtalk-desktop-home-")
            .toRealPath()
        if (Files.getFileAttributeView(home, PosixFileAttributeView::class.java) == null) {
            home.toFile().deleteRecursively()
            return
        }
        try {
            block(home.toFile())
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
