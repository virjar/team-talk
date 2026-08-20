package com.virjar.tk.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDataDirectorySecurityTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun `broad and unknown existing directories are rejected without chmod or marker creation`() {
        val broad = File("/etc")
        val broadMode = Files.getPosixFilePermissions(broad.toPath())
        val broadMarker = File(broad, ".tt-agent-data").exists()
        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openRuntime(broad, userHome = null)
        }
        assertEquals(broadMode, Files.getPosixFilePermissions(broad.toPath()))
        assertEquals(broadMarker, File(broad, ".tt-agent-data").exists())

        val unknown = File(temporaryRoot(), "unknown").also { assertTrue(it.mkdir()) }
        val permissive = PosixFilePermissions.fromString("rwxrwxrwx")
        Files.setPosixFilePermissions(unknown.toPath(), permissive)
        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openRuntime(unknown, userHome = null)
        }
        assertEquals(permissive, Files.getPosixFilePermissions(unknown.toPath()))
        assertTrue(unknown.listFiles().orEmpty().isEmpty())

        val unmarkedPrivate = File(temporaryRoot(), "unmarked-private").also { assertTrue(it.mkdir()) }
        Files.setPosixFilePermissions(
            unmarkedPrivate.toPath(),
            PosixFilePermissions.fromString("rwx------"),
        )
        val sentinel = File(unmarkedPrivate, "owned-by-someone-else").apply { writeText("keep") }
        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openRuntime(unmarkedPrivate, userHome = null)
        }
        assertEquals("keep", sentinel.readText())
        assertFalse(File(unmarkedPrivate, ".tt-agent-data").exists())
    }

    @Test
    fun `missing parent and symlinked parent chain are rejected without creating a leaf`() {
        val root = temporaryRoot()
        val missingParent = File(root, "missing/agent-data")
        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openRuntime(missingParent, userHome = null)
        }
        assertFalse(File(root, "missing").exists())

        val realParent = File(root, "real-parent").also { assertTrue(it.mkdir()) }
        val linkedParent = File(root, "linked-parent")
        Files.createSymbolicLink(linkedParent.toPath(), realParent.toPath())
        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openRuntime(File(linkedParent, "agent-data"), userHome = null)
        }
        assertFalse(File(realParent, "agent-data").exists())
    }

    @Test
    fun `temporary subtrees and writable parent chains are rejected before leaf creation`() {
        val tempLeaf = File(
            System.getProperty("java.io.tmpdir"),
            "tt-agent-must-not-create-${System.nanoTime()}",
        )
        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openRuntime(tempLeaf, userHome = null)
        }
        assertFalse(tempLeaf.exists())

        val root = temporaryRoot()
        val writableParent = File(root, "writable-parent").also { assertTrue(it.mkdir()) }
        val writableMode = PosixFilePermissions.fromString("rwxrwxrwx")
        Files.setPosixFilePermissions(writableParent.toPath(), writableMode)
        val child = File(writableParent, "agent-data")

        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openRuntime(child, userHome = null)
        }

        assertEquals(writableMode, Files.getPosixFilePermissions(writableParent.toPath()))
        assertFalse(child.exists())
    }

    @Test
    fun `service preparation creates one owned private dedicated leaf and validates it on restart`() {
        val parent = temporaryRoot()
        val parentPath = parent.toPath()
        val currentUid = (Files.getAttribute(parentPath, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
        val currentGid = (Files.getAttribute(parentPath, "unix:gid", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
        val identity = AgentUnixIdentity(
            userName = System.getProperty("user.name"),
            uid = currentUid.takeIf { it > 0 } ?: 65_534,
            gid = currentGid.takeIf { it > 0 } ?: 65_534,
        )
        val dataDir = File(parent, "service-data")

        val created = AgentDataDirectoryPolicy.prepareForService(dataDir, identity)
        val restarted = AgentDataDirectoryPolicy.prepareForService(dataDir, identity)

        assertEquals(created.root, restarted.root)
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(dataDir.toPath()),
        )
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(File(dataDir, ".tt-agent-data").toPath()),
        )
    }

    @Test
    fun `service install validation never creates an unprepared directory`() {
        val parent = temporaryRoot()
        val parentPath = parent.toPath()
        val currentUid = (Files.getAttribute(parentPath, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
        val currentGid = (Files.getAttribute(parentPath, "unix:gid", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
        val identity = AgentUnixIdentity(
            userName = "agent-service",
            uid = currentUid.takeIf { it > 0 } ?: 65_534,
            gid = currentGid.takeIf { it > 0 } ?: 65_534,
        )
        val dataDir = File(parent, "must-not-be-created")

        assertFailsWith<IllegalArgumentException> {
            AgentDataDirectoryPolicy.openPreparedForService(dataDir, identity)
        }

        assertFalse(dataDir.exists())
    }

    private fun temporaryRoot(): File =
        createAgentSecurityTestRoot("agent-data-security-").also(roots::add)
}
