package release

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkikoPackageChecksTest {
    private val packageNames = listOf(
        "private-0.0.0-34-windows-amd64.zip", "private-0.0.0-34.x64.msix",
        "private-0.0.0-34-mac-amd64.zip", "private-0.0.0-34-mac-aarch64.zip",
        "private-0.0.0-34-linux-amd64.tar.gz", "private_0.0.0-34_amd64.deb",
    )

    @Test
    fun `all six archives contain native files in the actual packaged JVM and remain unchanged`() = withDirectory { directory ->
        packageNames.forEach { name ->
            val archive = writeSkikoPackageFixture(directory.resolve(name))
            val before = sha256(archive)
            verifySkikoNativePackage(archive)
            assertEquals(before, sha256(archive))
        }
    }

    @Test
    fun `host only runtime and files placed outside java home do not satisfy another target`() = withDirectory { directory ->
        packageNames.forEach { name ->
            val (javaHome, natives) = skikoFixtureLayout(name)
            val libraryPath = javaHome + natives.first()
            val cases = listOf(
                // The original Windows/Linux output carried only this host runtime JAR.
                mapOf("app/skiko-awt-runtime-macos-x64.jar" to byteArrayOf(1)),
                mapOf("app/${natives.first().substringAfterLast('/')}" to byteArrayOf(1)),
                mapOf(libraryPath to byteArrayOf()),
                // A native nested inside an arbitrary JAR must not pass the extracted-library check.
                mapOf("app/unused-runtime.jar" to zipBytes(mapOf(natives.first().substringAfterLast('/') to byteArrayOf(1)))),
            )
            cases.forEachIndexed { index, files ->
                val content = mapOf(javaHome + "lib/modules" to byteArrayOf(1)) + files
                val archive = writeSkikoArchiveFixture(directory.resolve(name), content)
                val failure = assertFailsWith<IllegalArgumentException>("$name case $index") { verifySkikoNativePackage(archive) }
                assertTrue(failure.message.orEmpty().contains(libraryPath))
            }
        }
    }

    @Test
    fun `Windows extracted DLL also requires its ICU data beside it`() = withDirectory { directory ->
        packageNames.take(2).forEach { name ->
            val archive = writeSkikoArchiveFixture(directory.resolve(name), mapOf(
                "lib/modules" to byteArrayOf(1), "bin/skiko-windows-x64.dll" to byteArrayOf(1),
            ))
            val failure = assertFailsWith<IllegalArgumentException> { verifySkikoNativePackage(archive) }
            assertTrue(failure.message.orEmpty().contains("bin/icudtl.dat"))
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-skiko-package-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

/** Tiny, non-installable archives for release tests; MSIX also carries the existing manifest fixture. */
internal fun writeSkikoPackageFixture(file: File, msixManifest: String = msixManifestFixture()): File {
    val (javaHome, natives) = skikoFixtureLayout(file.name)
    val files = mutableMapOf(javaHome + "lib/modules" to byteArrayOf(1))
    natives.forEach { files[javaHome + it] = byteArrayOf(1, 2, 3) }
    if (file.extension == "msix") files["AppxManifest.xml"] = msixManifest.toByteArray(Charsets.UTF_8)
    return writeSkikoArchiveFixture(file, files)
}

private fun skikoFixtureLayout(name: String): Pair<String, List<String>> = when {
    name.endsWith("-windows-amd64.zip") || name.endsWith(".x64.msix") ->
        "" to listOf("bin/skiko-windows-x64.dll", "bin/icudtl.dat")
    name.endsWith("-mac-amd64.zip") ->
        "Private App.app/Contents/runtime/Contents/Home/" to listOf("lib/libskiko-macos-x64.dylib")
    name.endsWith("-mac-aarch64.zip") ->
        "Private App.app/Contents/runtime/Contents/Home/" to listOf("lib/libskiko-macos-arm64.dylib")
    name.endsWith("-linux-amd64.tar.gz") ->
        "private-0.0.0/lib/runtime/" to listOf("lib/libskiko-linux-x64.so")
    name.endsWith("_amd64.deb") ->
        "usr/lib/private/lib/runtime/" to listOf("lib/libskiko-linux-x64.so")
    else -> error("Unknown fixture package: $name")
}

private fun writeSkikoArchiveFixture(file: File, files: Map<String, ByteArray>): File {
    when {
        file.extension in setOf("zip", "msix") -> file.writeBytes(zipBytes(files))
        file.name.endsWith(".tar.gz") -> file.outputStream().use { output ->
            GzipCompressorOutputStream(output).use { it.write(tarBytes(files)) }
        }
        file.extension == "deb" -> {
            val data = ByteArrayOutputStream().apply {
                XZCompressorOutputStream(this).use { it.write(tarBytes(files)) }
            }.toByteArray()
            ArArchiveOutputStream(file.outputStream()).use { ar ->
                mapOf("debian-binary" to "2.0\n".toByteArray(), "data.tar.xz" to data).forEach { (name, bytes) ->
                    ar.putArchiveEntry(ArArchiveEntry(name, bytes.size.toLong()))
                    ar.write(bytes)
                    ar.closeArchiveEntry()
                }
            }
        }
        else -> error("Unknown fixture archive: $file")
    }
    return file
}

private fun zipBytes(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
    ZipOutputStream(this).use { zip ->
        files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}.toByteArray()

private fun tarBytes(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
    TarArchiveOutputStream(this).use { tar ->
        files.forEach { (name, bytes) ->
            tar.putArchiveEntry(TarArchiveEntry("./$name").apply { size = bytes.size.toLong() })
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
    }
}.toByteArray()
