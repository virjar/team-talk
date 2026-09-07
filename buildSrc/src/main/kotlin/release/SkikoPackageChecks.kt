package release

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Conveyor extracts native libraries into the packaged JVM. Skiko loads from java.home/bin on
 * Windows and java.home/lib elsewhere; an arbitrary runtime JAR in app/ is not sufficient.
 * Inspect archive entries without extracting files or changing signed package bytes.
 */
fun verifySkikoNativePackage(archive: File) {
    val layout = skikoPackageLayout(archive.name)
    val entries = when (layout.format) {
        SkikoArchiveFormat.ZIP -> ZipFile(archive).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }
                .associate { it.name.removePrefix("./") to it.size }
        }
        SkikoArchiveFormat.TAR_GZ -> archive.inputStream().buffered().use { input ->
            GzipCompressorInputStream(input).use { gzip -> TarArchiveInputStream(gzip).use(::tarFileSizes) }
        }
        SkikoArchiveFormat.DEB -> debFileSizes(archive)
    }
    val modulePaths = entries.filter { (name, size) -> size > 0 && layout.modules.matches(name) }.keys
    require(modulePaths.size == 1) { "Cannot locate the packaged JVM lib/modules in ${archive.name}" }
    val javaHome = modulePaths.single().removeSuffix("lib/modules")
    layout.nativeFiles.forEach { relative ->
        val path = javaHome + relative
        require((entries[path] ?: 0) > 0) {
            "Desktop package lacks Skiko's required runtime file $path: ${archive.name}. " +
                "Include the target platform runtime and extract it into the packaged JVM."
        }
    }
}

private enum class SkikoArchiveFormat { ZIP, TAR_GZ, DEB }

private data class SkikoPackageLayout(
    val format: SkikoArchiveFormat,
    val modules: Regex,
    val nativeFiles: List<String>,
)

private fun skikoPackageLayout(name: String): SkikoPackageLayout = when {
    name.endsWith("-windows-amd64.zip") || name.endsWith(".x64.msix") -> SkikoPackageLayout(
        SkikoArchiveFormat.ZIP, Regex("lib/modules"),
        // Library.load passes icudtl.dat as additionalFile; both must exist for this loading path.
        listOf("bin/skiko-windows-x64.dll", "bin/icudtl.dat"),
    )
    name.endsWith("-mac-amd64.zip") -> SkikoPackageLayout(
        SkikoArchiveFormat.ZIP, Regex("[^/]+\\.app/Contents/runtime/Contents/Home/lib/modules"),
        listOf("lib/libskiko-macos-x64.dylib"),
    )
    name.endsWith("-mac-aarch64.zip") -> SkikoPackageLayout(
        SkikoArchiveFormat.ZIP, Regex("[^/]+\\.app/Contents/runtime/Contents/Home/lib/modules"),
        listOf("lib/libskiko-macos-arm64.dylib"),
    )
    name.endsWith("-linux-amd64.tar.gz") -> SkikoPackageLayout(
        SkikoArchiveFormat.TAR_GZ, Regex("[^/]+/lib/runtime/lib/modules"),
        listOf("lib/libskiko-linux-x64.so"),
    )
    name.endsWith("_amd64.deb") -> SkikoPackageLayout(
        SkikoArchiveFormat.DEB, Regex("usr/lib/[^/]+/lib/runtime/lib/modules"),
        listOf("lib/libskiko-linux-x64.so"),
    )
    else -> throw IllegalArgumentException("Unsupported Desktop package for Skiko verification: $name")
}

private fun tarFileSizes(tar: TarArchiveInputStream): Map<String, Long> = buildMap {
    while (true) {
        val entry = tar.nextEntry ?: break
        if (entry.isFile) put(entry.name.removePrefix("./"), entry.size)
    }
}

private fun debFileSizes(archive: File): Map<String, Long> =
    ArArchiveInputStream(archive.inputStream().buffered()).use { ar ->
        while (true) {
            val entry = ar.nextEntry ?: break
            if (entry.name == "data.tar" || entry.name.startsWith("data.tar.")) {
                val payload = if (entry.name == "data.tar") ar else
                    CompressorStreamFactory().createCompressorInputStream(ar.buffered())
                return@use TarArchiveInputStream(payload).use(::tarFileSizes)
            }
        }
        throw IllegalArgumentException("Debian package lacks its data archive: ${archive.name}")
    }
