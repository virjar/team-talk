package com.virjar.tk.agent

import com.virjar.tk.repository.UploadSink
import com.virjar.tk.repository.UploadSource
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFileAccessPolicyTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun `upload snapshots a canonical regular file then deletes private staging`() = runBlocking {
        val dataDir = File(temporaryRoot(), "agent-data")
        val policy = AgentFileAccessPolicy(dataDir)
        val nested = File(dataDir, "outgoing/reports").also { assertTrue(it.mkdir()) }
        val source = File(nested, "daily.txt").apply { writeText("original") }

        val staged = policy.stageUpload(source.path)
        val stagingPath = staged.stagingPath
        try {
            assertTrue(Files.exists(stagingPath))
            assertTrue(stagingPath.startsWith(File(dataDir, ".staging").canonicalFile.toPath()))
            assertTrue(stagingPath.fileName.toString().endsWith(".ready"))
            assertTrue(
                Files.getPosixFilePermissions(stagingPath) ==
                    PosixFilePermissions.fromString("rw-------"),
            )
            assertContentEquals("original".toByteArray(), staged.source.readAll())

            assertTrue(source.delete())
            source.writeText("replacement")
            assertContentEquals(
                "original".toByteArray(),
                staged.source.readAll(),
                "upload retries must never reopen the caller-controlled path",
            )
        } finally {
            staged.close()
        }
        assertFalse(Files.exists(stagingPath))
    }

    @Test
    fun `parent traversal data secrets and broad roots are rejected`() {
        val root = temporaryRoot()
        val dataDir = File(root, "agent-data")
        AgentCredentials.ensureIdentity(dataDir)
        val policy = AgentFileAccessPolicy(dataDir)

        assertFailsWith<IllegalArgumentException> {
            policy.stageUpload(File(dataDir, "outgoing/../credentials.properties").path)
        }
        assertFailsWith<IllegalArgumentException> {
            policy.stageUpload(File(dataDir, "credentials.properties").path)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentFileAccessPolicy(File(File.listRoots().first().path))
        }
        listOf("/etc", "/var", "/var/lib", "/opt", "/usr", "/root", "/home", "/Users").forEach { broadRoot ->
            assertFailsWith<IllegalArgumentException> {
                AgentFileAccessPolicy(File(broadRoot), userHome = null)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AgentFileAccessPolicy(root, userHome = root)
        }
    }

    @Test
    fun `symlink and hard-link sources are rejected before staging`() {
        val root = temporaryRoot()
        val dataDir = File(root, "agent-data")
        AgentCredentials.ensureIdentity(dataDir)
        val policy = AgentFileAccessPolicy(dataDir)
        val outgoing = File(dataDir, "outgoing")
        val outside = File(root, "outside.txt").apply { writeText("outside") }
        val symlink = File(outgoing, "escape.txt")
        Files.createSymbolicLink(symlink.toPath(), outside.toPath())

        assertFailsWith<IllegalArgumentException> {
            policy.stageUpload(symlink.path)
        }

        val source = File(outgoing, "source.txt").apply { writeText("hard-linked") }
        val hardLink = File(outgoing, "hard-link.txt")
        Files.createLink(hardLink.toPath(), source.toPath())
        assertFailsWith<IllegalArgumentException> {
            policy.stageUpload(hardLink.path)
        }
        assertTrue(File(dataDir, ".staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `replacing a private staging path cannot redirect upload retries`() = runBlocking {
        val root = temporaryRoot()
        val dataDir = File(root, "agent-data")
        val policy = AgentFileAccessPolicy(dataDir)
        val source = File(dataDir, "outgoing/source.txt").apply { writeText("trusted") }
        val staged = policy.stageUpload(source.path)
        val stagingPath = staged.stagingPath
        val outside = File(root, "outside-secret.txt").apply { writeText("secret!") }

        Files.delete(stagingPath)
        Files.createSymbolicLink(stagingPath, outside.toPath())
        try {
            assertFailsWith<IllegalStateException> { staged.source.readAll() }
        } finally {
            staged.close()
        }

        assertFalse(Files.exists(stagingPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("secret!", outside.readText())
    }

    @Test
    fun `startup removes only owned orphan staging names without following links`() {
        val root = temporaryRoot()
        val dataDir = File(root, "agent-data")
        AgentFileAccessPolicy(dataDir)
        val staging = File(dataDir, ".staging")
        val partial = File(staging, ".upload-crash.partial").apply { writeText("partial") }
        val ready = File(staging, ".upload-crash.partial.ready").apply { writeText("ready") }
        val unrelated = File(staging, "operator-note").apply { writeText("keep") }
        val outside = File(root, "outside.txt").apply { writeText("outside") }
        val orphanLink = File(staging, ".upload-link.partial.ready")
        Files.createSymbolicLink(orphanLink.toPath(), outside.toPath())

        AgentFileAccessPolicy(dataDir)

        assertFalse(partial.exists())
        assertFalse(ready.exists())
        assertFalse(Files.exists(orphanLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("outside", outside.readText())
        assertEquals("keep", unrelated.readText())
    }

    private suspend fun UploadSource.readAll(): ByteArray {
        val output = ByteArrayOutputStream()
        writeTo(UploadSink { bytes, offset, length -> output.write(bytes, offset, length) })
        return output.toByteArray()
    }

    private fun temporaryRoot(): File =
        createAgentSecurityTestRoot("agent-file-policy-").also(roots::add)
}
