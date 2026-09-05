package release

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Git fixtures exercise the same source-of-truth boundary used on private Windows workstations and in CI. */
class ReleaseMetadataTest {
    @Test
    fun `only a readable version bump with a higher installation counter requests a release`() = repository { repo ->
        repo.writeVersion("0.0.0", 0)
        val baseline = repo.commit("Initial preview")
        val metadata = ReleaseMetadata(repo.root)

        repo.writeVersion("0.0.0", 0, protocolMinor = 8)
        repo.commit("Experiment with provisional protocol versions")
        assertFalse(metadata.releaseChangedSince(baseline))
        repo.root.resolve("gradle.properties").appendText("org.gradle.jvmargs=-Xmx4g\n")
        repo.commit("Adjust the development JVM budget")
        assertFalse(metadata.releaseChangedSince(baseline))

        repo.writeVersion("0.0.1", 1, protocolMinor = 1)
        repo.commit("Prepare the next preview")
        assertTrue(metadata.releaseChangedSince(baseline))
    }

    @Test
    fun `half bumps and version rollbacks are rejected`() = repository { repo ->
        repo.writeVersion("1.2.3", 10)
        val baseline = repo.commit("Established preview")
        val metadata = ReleaseMetadata(repo.root)
        listOf("1.2.4" to 10, "1.2.3" to 11, "1.2.2" to 11, "1.2.4" to 9).forEach { (version, build) ->
            repo.writeVersion(version, build)
            repo.commit("Invalid candidate $version build $build")
            assertFailsWith<IllegalArgumentException> { metadata.releaseChangedSince(baseline) }
        }
    }

    @Test
    fun `committed human notes are preserved verbatim and callers cannot override root versions`() = repository { repo ->
        repo.writeVersion("0.1.0", 7)
        val authored = "# TeamTalk 0.1.0\n\n保留草稿与账号；修复重连后的消息状态。\n\n升级影响：无需重新登录。\n"
        repo.writeNotes("0.1.0", authored)
        val revision = repo.commit("Describe the preview in maintained release notes")
        val metadata = ReleaseMetadata(repo.root)
        val version = ReleaseVersion.read(repo.root)
        assertEquals(authored, metadata.verify(version, revision))
        assertFailsWith<IllegalArgumentException> { metadata.verify(version.copy(name = "9.9.9"), revision) }
        assertFailsWith<IllegalArgumentException> { metadata.verify(version.copy(buildNumber = 99), revision) }
    }

    @Test
    fun `missing or incomplete notes fail even when all source changes are committed`() = repository { repo ->
        repo.writeVersion("0.0.1", 1)
        var revision = repo.commit("Version without notes")
        val metadata = ReleaseMetadata(repo.root)
        val version = ReleaseVersion.read(repo.root)
        assertFailsWith<IllegalArgumentException> { metadata.verify(version, revision) }
        listOf("# TeamTalk 0.0.1\n", "# TeamTalk 0.0.0\n\nWrong version.\n").forEach { notes ->
            repo.writeNotes("0.0.1", notes)
            revision = repo.commit("Incomplete maintained notes")
            assertFailsWith<IllegalArgumentException> { metadata.verify(version, revision) }
        }
    }

    @Test
    fun `dirty notes untracked files and a changed HEAD cannot be published`() = repository { repo ->
        repo.writeVersion("0.0.1", 1)
        val notes = "# TeamTalk 0.0.1\n\nMaintain existing user data.\n"
        repo.writeNotes("0.0.1", notes)
        val revision = repo.commit("Ready to release")
        val version = ReleaseVersion.read(repo.root)
        val metadata = ReleaseMetadata(repo.root)
        assertEquals(notes, metadata.verify(version, revision))

        repo.writeNotes("0.0.1", notes + "Uncommitted clarification.\n")
        assertFailsWith<IllegalArgumentException> { metadata.verify(version, revision) }
        repo.writeNotes("0.0.1", notes)
        val untracked = repo.root.resolve("untracked-source.kt").apply { writeText("// Unreviewed work\n") }
        assertFailsWith<IllegalArgumentException> { metadata.verify(version, revision) }
        assertTrue(untracked.delete())
        assertEquals(notes, metadata.verify(version, revision))

        repo.root.resolve("another-source.kt").writeText("// A different source revision\n")
        repo.commit("Source moved after release started")
        assertFailsWith<IllegalArgumentException> { metadata.verify(version, revision) }
    }

    @Test
    fun `commit appendix contains exactly the changes after the previous release tag`() = repository { repo ->
        repo.writeVersion("0.0.0", 0)
        repo.commit("BASELINE_OUTSIDE_THE_RANGE")
        repo.git("tag", "v0.0.0")

        repo.root.resolve("reconnect.kt").writeText("// Reconnect fix\n")
        repo.commit("Fix reconnect recovery")
        repo.writeVersion("0.0.1", 1)
        val notes = "# TeamTalk 0.0.1\n\n维护者确认：修复重连，保留本地数据。\n"
        repo.writeNotes("0.0.1", notes)
        val current = repo.commit("Prepare the next preview and upgrade guidance")
        repo.git("tag", "v0.0.1")
        // Unrelated tags should not change the selected release range.
        repo.git("tag", "development-checkpoint")

        val metadata = ReleaseMetadata(repo.root)
        val version = ReleaseVersion.read(repo.root)
        val appendix = metadata.commitAppendix(version)
        assertTrue(appendix.contains("Range: v0.0.0 → v0.0.1."))
        assertTrue(appendix.contains("Fix reconnect recovery"))
        assertTrue(appendix.contains("Prepare the next preview and upgrade guidance"))
        assertFalse(appendix.contains("BASELINE_OUTSIDE_THE_RANGE"))
        assertEquals(notes, metadata.verify(version, current))
        assertEquals(notes, metadata.notesFile(version).readText())
    }

    private fun repository(action: (GitRepository) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk release metadata ").toFile()
        try {
            val repo = GitRepository(directory)
            repo.git("init")
            repo.git("config", "user.name", "TeamTalk release fixture")
            repo.git("config", "user.email", "release-fixture@example.invalid")
            repo.git("config", "commit.gpgsign", "false")
            repo.git("config", "tag.gpgsign", "false")
            repo.git("config", "core.autocrlf", "false")
            repo.git("config", "core.hooksPath", directory.resolve(".git/disabled-fixture-hooks").absolutePath)
            action(repo)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class GitRepository(val root: File) {
        fun writeVersion(version: String, build: Int, protocolMinor: Int = 0) {
            root.resolve("gradle.properties").writeText(
                "teamtalk.releaseVersion=$version\n" +
                    "teamtalk.releaseBuildNumber=$build\n" +
                    "teamtalk.protocolMajor=0\n" +
                    "teamtalk.protocolMinor=$protocolMinor\n" +
                    "teamtalk.minimumProtocolMinor=0\n",
            )
        }

        fun writeNotes(version: String, text: String) {
            root.resolve("doc/07-operations/releases/$version.md").apply {
                parentFile.mkdirs()
                writeText(text)
            }
        }

        fun commit(message: String): String {
            git("add", "--all")
            git("commit", "--quiet", "--message", message)
            return git("rev-parse", "HEAD")
        }

        fun git(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
            check(process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Timed out creating the temporary Git release fixture"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.exitValue() == 0) { "Git release fixture failed: $output" }
            return output.trim()
        }
    }
}
