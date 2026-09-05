package deployment

import java.io.File
import java.nio.file.Files
import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseArtifactContractTest {
    @Test
    fun `staged artifact must match type version and exact build identity`() = withTempDirectory { artifact ->
        File(artifact, "bin/server").apply {
            parentFile.mkdirs()
            writeText("payload")
        }
        writeReleaseArtifactManifest(
            artifact,
            "server-distribution",
            TEST_VERSION,
            TEST_BUILD_IDENTITY,
            ServerProtocolWindow(0, 2, 5),
        )

        val identity = requireReleaseArtifact(
            artifact,
            "server-distribution",
            TEST_VERSION,
            TEST_BUILD_IDENTITY,
        )
        assertEquals(TEST_BUILD_IDENTITY, identity.buildIdentity)
        assertEquals(ServerProtocolWindow(0, 2, 5), identity.serverProtocol)
        assertFailsWith<GradleException> {
            requireReleaseArtifact(artifact, "desktop-site", TEST_VERSION, TEST_BUILD_IDENTITY)
        }
        assertFailsWith<GradleException> {
            requireReleaseArtifact(artifact, "server-distribution", "1.0.8", TEST_BUILD_IDENTITY)
        }
        assertFailsWith<GradleException> {
            requireReleaseArtifact(artifact, "server-distribution", TEST_VERSION, "another-build")
        }
    }

    @Test
    fun `staged server requires its own complete protocol window before deployment`() =
        withTempDirectory { artifact ->
            File(artifact, "payload").writeText("server")
            writeReleaseArtifactManifest(artifact, "server-distribution", TEST_VERSION, TEST_BUILD_IDENTITY)
            val manifest = File(artifact, RELEASE_ARTIFACT_MANIFEST_FILE)
            val legacy = manifest.readText()
            val windows = listOf(
                "",
                "protocolMajor=0\nminimumProtocolMinor=2",
                "protocolMajor=0\nminimumProtocolMinor=6\nprotocolMinor=5",
                "protocolMajor=0\nminimumProtocolMinor=02\nprotocolMinor=5",
                "protocolMajor=32768\nminimumProtocolMinor=0\nprotocolMinor=5",
                "protocolMajor=0\nminimumProtocolMinor=0\nprotocolMinor=65536",
            )
            windows.forEach { invalid ->
                manifest.writeText(legacy + invalid + "\n")
                assertFailsWith<GradleException>(invalid) {
                    requireReleaseArtifact(artifact, "server-distribution", TEST_VERSION, TEST_BUILD_IDENTITY)
                }
            }
            manifest.writeText(legacy + "protocolMajor=1\nminimumProtocolMinor=3\nprotocolMinor=9\n")
            assertEquals(
                ServerProtocolWindow(1, 3, 9),
                requireReleaseArtifact(artifact, "server-distribution", TEST_VERSION, TEST_BUILD_IDENTITY).serverProtocol,
            )
        }

    @Test
    fun `manifest alone is not a deployable artifact`() = withTempDirectory { artifact ->
        writeReleaseArtifactManifestFile(
            File(artifact, RELEASE_ARTIFACT_MANIFEST_FILE),
            "desktop-site",
            TEST_VERSION,
            TEST_BUILD_IDENTITY,
        )

        assertFailsWith<GradleException> {
            requireReleaseArtifact(artifact, "desktop-site", TEST_VERSION, TEST_BUILD_IDENTITY)
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-release-artifact-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private const val TEST_VERSION = "1.0.7"
private const val TEST_BUILD_IDENTITY = "1.0.7+0123456789abcdef0123456789abcdef01234567"
