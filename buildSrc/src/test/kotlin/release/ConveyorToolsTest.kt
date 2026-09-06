package release

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException

class ConveyorToolsTest {
    @Test
    fun `verified download is cached and damaged installs are rebuilt`() = temporaryDirectory { directory ->
        val archive = createZip(directory.resolve("tool.zip"), "bin/conveyor", "launcher")
        val distribution = fixtureDistribution(archive)
        var downloads = 0
        val installer = ConveyorInstaller(directory.resolve("cache")) { _, destination ->
            downloads++
            Files.copy(archive, destination)
        }
        val first = installer.install(distribution, "https://tools.example.invalid")
        assertEquals("launcher", first.readText())
        assertEquals(first, installer.install(distribution, "https://tools.example.invalid"))
        assertEquals(1, downloads)
        assertTrue(first.delete())
        assertEquals("launcher", installer.install(distribution, "https://tools.example.invalid").readText())
        assertEquals(2, downloads)
    }

    @Test
    fun `checksum failure leaves no installed marker and can be retried`() = temporaryDirectory { directory ->
        val archive = createZip(directory.resolve("tool.zip"), "bin/conveyor", "launcher")
        val distribution = fixtureDistribution(archive)
        val cache = directory.resolve("cache")
        var corrupt = true
        val installer = ConveyorInstaller(cache) { _, destination ->
            if (corrupt) Files.writeString(destination, "interrupted download") else Files.copy(archive, destination)
        }
        assertFailsWith<IllegalStateException> { installer.install(distribution, "https://tools.example.invalid") }
        assertFalse(cache.toFile().walkTopDown().any { it.name == ".archive-sha256" || it.name.contains("staging-") })
        corrupt = false
        assertTrue(installer.install(distribution, "https://tools.example.invalid").isFile)
    }

    @Test
    fun `zip traversal and escaping symbolic links are rejected before installation`() = temporaryDirectory { directory ->
        val traversal = createZip(directory.resolve("traversal.zip"), "../outside", "escaped")
        assertFailsWith<IllegalArgumentException> { extractConveyorArchive(traversal, directory.resolve("out")) }
        assertFalse(Files.exists(directory.resolve("outside")))

        val linkArchive = directory.resolve("link.zip")
        ZipArchiveOutputStream(linkArchive).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("link").apply { unixMode = 0b1010000000000000 or 0b111111111 })
            zip.write("../outside".toByteArray())
            zip.closeArchiveEntry()
        }
        assertFailsWith<IllegalArgumentException> { extractConveyorArchive(linkArchive, directory.resolve("links")) }
        assertFalse(Files.isSymbolicLink(directory.resolve("links/link")))
    }

    @Test
    fun `tar preserves native launcher permission and internal links`() = temporaryDirectory { directory ->
        if (System.getProperty("os.name").startsWith("Windows")) return@temporaryDirectory
        val archive = directory.resolve("tool.tar.gz")
        GzipCompressorOutputStream(Files.newOutputStream(archive)).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("tool/bin/conveyor").apply {
                    size = 8
                    mode = 0b111101101
                })
                tar.write("launcher".toByteArray())
                tar.closeArchiveEntry()
                tar.putArchiveEntry(TarArchiveEntry("tool/bin/alias", TarConstants.LF_SYMLINK).apply { linkName = "conveyor" })
                tar.closeArchiveEntry()
            }
        }
        val unpacked = directory.resolve("unpacked")
        extractConveyorArchive(archive, unpacked)
        assertTrue(Files.isExecutable(unpacked.resolve("tool/bin/conveyor")))
        assertTrue(Files.isSymbolicLink(unpacked.resolve("tool/bin/alias")))
        assertEquals("launcher", Files.readString(unpacked.resolve("tool/bin/alias")))
    }

    @Test
    fun `Windows and Apple silicon host names resolve explicitly`() {
        assertEquals("windows-amd64", conveyorHostPlatform("Windows 11", "amd64"))
        assertEquals("mac-aarch64", conveyorHostPlatform("Mac OS X", "aarch64"))
        assertEquals("linux-amd64", conveyorHostPlatform("Linux", "x86_64"))
        assertFailsWith<IllegalStateException> { conveyorHostPlatform("Linux", "riscv64") }
    }

    @Test
    fun `signing configuration requires an existing identity without exposing parse errors`() = temporaryDirectory { directory ->
        val defaults = directory.resolve("defaults.conf")
        Files.writeString(defaults, "app.signing-key = \"fixture-key\"")
        requireConveyorSigningConfiguration(directory.toFile(), emptyMap())
        Files.writeString(defaults, "app.signing-key = \${env.FIXTURE_KEY}")
        requireConveyorSigningConfiguration(directory.toFile(), mapOf("FIXTURE_KEY" to "fixture-key"))
        assertFailsWith<GradleException> { requireConveyorSigningConfiguration(directory.toFile(), emptyMap()) }
        Files.writeString(defaults, "app.signing-key = \"sensitive-fixture-value")
        val failure = assertFailsWith<GradleException> { requireConveyorSigningConfiguration(directory.toFile(), emptyMap()) }
        assertFalse(failure.message.orEmpty().contains("sensitive-fixture-value"))
        assertEquals(null, failure.cause)
        Files.writeString(defaults, "app.signing-key = \"\"")
        assertFailsWith<GradleException> { requireConveyorSigningConfiguration(directory.toFile(), emptyMap()) }
        Files.writeString(defaults, "app.display-name = \"TeamTalk\"")
        assertFailsWith<GradleException> { requireConveyorSigningConfiguration(directory.toFile(), emptyMap()) }
    }

    private fun createZip(path: Path, name: String, content: String): Path {
        ZipArchiveOutputStream(path).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry(name).apply { unixMode = 0b1000000000000000 or 0b111101101 })
            zip.write(content.toByteArray())
            zip.closeArchiveEntry()
        }
        return path
    }

    private fun fixtureDistribution(archive: Path) = ConveyorDistribution(
        "22.1", "test-host", archive.fileName.toString(), sha256File(archive), "bin/conveyor",
    )

    private fun temporaryDirectory(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-conveyor-test-")
        try {
            action(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
