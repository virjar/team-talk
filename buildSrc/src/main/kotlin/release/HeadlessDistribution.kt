package release

import deployment.RELEASE_ARTIFACT_MANIFEST_FILE
import deployment.requireReleaseArtifact
import deployment.writeReleaseArtifactManifest
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile as PortableZipFile

/** A portable SDK distribution, independent of Desktop installation names and server configuration. */
object HeadlessDistribution {
    const val ARTIFACT_TYPE = "headless-distribution"
    const val CHECKSUMS = "SHA256SUMS"
    const val ARCHIVE_ROOT = "tt-headless"
    private val entryPoints = linkedMapOf(
        "tt-agent" to "com.virjar.tk.shared.agent.AgentMainKt",
        "tt" to "com.virjar.tk.shared.agent.CliMainKt",
        "tt-mcp" to "com.virjar.tk.shared.agent.McpMainKt",
    )

    fun archiveName(buildIdentity: String): String {
        require(buildIdentity.matches(Regex("[A-Za-z0-9.+-]+"))) { "Unsafe headless archive identity" }
        return "TeamTalk-$buildIdentity-headless.zip"
    }

    /** Called after Sync has copied the current JVM jar, complete runtime classpath and license. */
    fun seal(directory: File, version: ReleaseVersion, buildIdentity: String) {
        requirePayload(directory)
        entryPoints.forEach { (name, mainClass) ->
            File(directory, "bin/$name").apply {
                parentFile.mkdirs()
                writeText(unixLauncher(mainClass, name == "tt-mcp"))
                setExecutable(true, false)
            }
            File(directory, "bin/$name.bat").writeText(windowsLauncher(mainClass, name))
        }
        writeReleaseArtifactManifest(directory, ARTIFACT_TYPE, version.name, buildIdentity)
        File(directory, RELEASE_ARTIFACT_MANIFEST_FILE).appendText(buildString {
            extraProperties(version).forEach { (key, value) -> appendLine("$key=$value") }
        })
        File(directory, CHECKSUMS).writeText(checksumText(directory))
        verify(directory, version, buildIdentity)
    }

    fun verify(directory: File, version: ReleaseVersion, buildIdentity: String) {
        val files = regularFiles(directory).associateBy { it.relativeTo(directory).invariantSeparatorsPath }
        requireReleaseArtifact(directory, ARTIFACT_TYPE, version.name, buildIdentity)
        requireLayout(files.keys)
        requirePayload(directory)
        requireManifest(Properties().apply {
            files.getValue(RELEASE_ARTIFACT_MANIFEST_FILE).reader(Charsets.UTF_8).use(::load)
        }, version, buildIdentity)
        require(files.getValue(CHECKSUMS).readText() == checksumText(directory)) {
            "Headless SHA256SUMS does not match its complete file inventory"
        }
    }

    /** Fixed timestamps and Unix modes make the same input bytes portable across packaging hosts. */
    fun archive(directory: File, destination: File, version: ReleaseVersion, buildIdentity: String) {
        verify(directory, version, buildIdentity)
        destination.parentFile.mkdirs()
        ZipArchiveOutputStream(destination).use { zip ->
            regularFiles(directory).forEach { file ->
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                val entry = ZipArchiveEntry("$ARCHIVE_ROOT/$relative").apply {
                    time = 0L
                    unixMode = if (relative.startsWith("bin/") && !relative.endsWith(".bat")) 0b111101101 else 0b110100100
                }
                zip.putArchiveEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeArchiveEntry()
            }
        }
        verifyArchive(destination, version, buildIdentity)
    }

    /** ReleaseBundle consumes the archive's own identity and byte inventory, never the current build directory. */
    fun verifyArchive(archive: File, version: ReleaseVersion, buildIdentity: String) {
        PortableZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries.asSequence().take(4097).toList()
            require(entries.isNotEmpty() && entries.size <= 4096 && entries.none {
                it.isDirectory || it.isUnixSymlink || (it.unixMode and 0xF000) !in setOf(0, 0x8000)
            }) {
                "Headless ZIP must contain only its bounded regular-file inventory"
            }
            val names = entries.map { it.name }
            require(names.distinct().size == names.size && names.all { it.startsWith("$ARCHIVE_ROOT/") }) {
                "Headless ZIP has duplicate entries or an unexpected root"
            }
            val files = entries.associateBy { it.name.removePrefix("$ARCHIVE_ROOT/").also(::requireSafePath) }
            requireLayout(files.keys)
            fun metadata(name: String): String = zip.getInputStream(files.getValue(name)).use { input ->
                val bytes = input.readNBytes(1_048_577)
                require(bytes.size <= 1_048_576) { "Headless metadata is too large" }
                bytes.toString(Charsets.UTF_8)
            }
            requireManifest(Properties().apply { metadata(RELEASE_ARTIFACT_MANIFEST_FILE).reader().use(::load) },
                version, buildIdentity)
            val checksums = files.keys.sorted().filterNot { it == CHECKSUMS }.joinToString("") { relative ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                zip.getInputStream(files.getValue(relative)).use { stream ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                "${digest.digest().joinToString("") { "%02x".format(it) }}  $relative\n"
            }
            require(metadata(CHECKSUMS) == checksums) { "Headless ZIP checksum or file inventory mismatch" }
        }
    }

    private fun extraProperties(version: ReleaseVersion) = linkedMapOf(
        "releaseBuildNumber" to version.buildNumber.toString(),
        "protocolMajor" to version.protocolMajor.toString(),
        "protocolMinor" to version.protocolMinor.toString(),
        "minimumJavaVersion" to "21",
    )

    private fun requireManifest(properties: Properties, version: ReleaseVersion, buildIdentity: String) {
        val expected = extraProperties(version) + mapOf(
            "artifactType" to ARTIFACT_TYPE, "version" to version.name, "buildIdentity" to buildIdentity,
        )
        require(expected.all { (key, value) -> properties.getProperty(key) == value }) {
            "Headless artifact belongs to another build, protocol or Java requirement"
        }
    }

    private fun requireLayout(files: Set<String>) {
        val required = entryPoints.keys.flatMap { listOf("bin/$it", "bin/$it.bat") } +
            listOf("LICENSE", RELEASE_ARTIFACT_MANIFEST_FILE, CHECKSUMS)
        require(files.containsAll(required) && files.any { it.startsWith("lib/") && it.endsWith(".jar") }) {
            "Headless distribution needs all launchers, JVM dependencies, identity, license and checksums"
        }
    }

    private fun requirePayload(directory: File) {
        val jars = regularFiles(directory).filter { it.parentFile == File(directory, "lib") && it.extension == "jar" }
        val required = entryPoints.values.mapTo(mutableSetOf()) { it.replace('.', '/') + ".class" }
        jars.forEach { jar -> ZipFile(jar).use { zip -> required.removeAll { zip.getEntry(it) != null } } }
        require(required.isEmpty() && File(directory, "LICENSE").isFile) { "Headless JVM entry points or license are missing" }
    }

    private fun checksumText(directory: File): String = regularFiles(directory)
        .filterNot { it == File(directory, CHECKSUMS) }
        .joinToString("") { "${sha256(it)}  ${it.relativeTo(directory).invariantSeparatorsPath}\n" }

    private fun regularFiles(directory: File): List<File> = directory.walkTopDown().onEnter {
        require(!Files.isSymbolicLink(it.toPath())) { "Headless distribution cannot contain symbolic links" }
        true
    }.filter {
        require(!Files.isSymbolicLink(it.toPath())) { "Headless distribution cannot contain symbolic links" }
        require(it.isDirectory || it.isFile) { "Headless distribution must contain only regular files" }
        it.isFile
    }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath.also(::requireSafePath) }.toList().also {
        require(it.size <= 4096) { "Headless distribution contains too many files" }
    }

    private fun requireSafePath(path: String) {
        require(path.length <= 512 && path.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/+\\-]*")) &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe headless artifact path" }
    }

    private fun unixLauncher(mainClass: String, ipv4: Boolean): String = """
        |#!/bin/sh
        |# Requires JDK 21 or newer. Resolve symlinked installation directories without changing the caller's cwd.
        |APP_HOME=${'$'}(CDPATH= cd -P "${'$'}(dirname "${'$'}0")/.." && pwd -P) || exit 1
        |if [ "${'$'}{1-}" = "--version" ]; then
        |    cat "${'$'}APP_HOME/$RELEASE_ARTIFACT_MANIFEST_FILE" || exit 1
        |    printf 'distributionDirectory=%s\n' "${'$'}APP_HOME"
        |    exit 0
        |fi
        |if [ -n "${'$'}{JAVA_HOME:-}" ]; then
        |    JAVA_CMD="${'$'}JAVA_HOME/bin/java"
        |else
        |    JAVA_CMD=java
        |fi
        |if ! command -v "${'$'}JAVA_CMD" >/dev/null 2>&1; then
        |    printf '%s\n' 'JDK 21 or newer is required. Set JAVA_HOME or put java on PATH.' >&2
        |    exit 1
        |fi
        |exec "${'$'}JAVA_CMD" "-Dteamtalk.headless.bundle=${'$'}APP_HOME" ${if (ipv4) "-Djava.net.preferIPv4Stack=true " else ""}-cp "${'$'}APP_HOME/lib/*" $mainClass "${'$'}@"
        |
    """.trimMargin()

    private fun windowsLauncher(mainClass: String, name: String): String = buildString {
        appendLine("@echo off")
        appendLine("setlocal DisableDelayedExpansion")
        appendLine("for %%I in (\"%~dp0..\") do set \"APP_HOME=%%~fI\"")
        appendLine("if \"%~1\"==\"--version\" goto version")
        if (name != "tt") {
            appendLine("echo $name requires Linux or macOS with a POSIX filesystem. Windows supports the tt CLI launcher. 1>&2")
            appendLine("exit /b 1")
        } else {
            appendLine("if defined JAVA_HOME (set \"JAVA_CMD=%JAVA_HOME%\\bin\\java.exe\") else (set \"JAVA_CMD=java.exe\")")
            appendLine("\"%JAVA_CMD%\" \"-Dteamtalk.headless.bundle=%APP_HOME%\" ${if (name == "tt-mcp") "-Djava.net.preferIPv4Stack=true " else ""}-cp \"%APP_HOME%\\lib\\*\" $mainClass %*")
            appendLine("exit /b %ERRORLEVEL%")
        }
        appendLine(":version")
        appendLine("type \"%APP_HOME%\\$RELEASE_ARTIFACT_MANIFEST_FILE\"")
        appendLine("if errorlevel 1 exit /b 1")
        appendLine("echo distributionDirectory=\"%APP_HOME%\"")
        appendLine("exit /b 0")
    }.replace("\n", "\r\n")
}
