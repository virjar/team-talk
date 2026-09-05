package release

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigValueType
import deployment.ProcessOutputMode
import deployment.ProcessSpec
import deployment.runCheckedProcess
import deployment.writeReleaseArtifactManifest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Properties

/** The Gradle plugin extracts configuration; this installer owns the separately distributed CLI. */
data class ConveyorDistribution(
    val version: String,
    val platform: String,
    val archive: String,
    val sha256: String,
    val executable: String,
) {
    companion object {
        fun read(catalog: File, platform: String = conveyorHostPlatform()): ConveyorDistribution {
            val values = Properties().apply { catalog.inputStream().use(::load) }
            fun required(key: String) = requireNotNull(values.getProperty(key)) {
                "Conveyor distribution $platform is not pinned in ${catalog.name}: missing $key"
            }.trim().also { require(it.isNotEmpty()) { "Empty Conveyor distribution setting: $key" } }
            return ConveyorDistribution(
                required("version"), platform, required("$platform.archive"),
                required("$platform.sha256"), required("$platform.executable"),
            ).also {
                require(it.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid Conveyor archive SHA-256" }
                require(it.archive.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid Conveyor archive name" }
                require(it.version.matches(Regex("[0-9]+(\\.[0-9]+)+"))) { "Invalid Conveyor version" }
            }
        }
    }
}

fun conveyorHostPlatform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String {
    val cpu = when (arch.lowercase()) {
        "amd64", "x86_64", "x64" -> "amd64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("Conveyor has no pinned host distribution for $os / $arch")
    }
    val system = when {
        os.startsWith("Mac", ignoreCase = true) -> "mac"
        os.startsWith("Windows", ignoreCase = true) -> "windows"
        os.startsWith("Linux", ignoreCase = true) -> "linux"
        else -> error("Conveyor has no pinned host distribution for $os / $arch")
    }
    return "$system-$cpu"
}

/** Installs into a private staging directory; only a fully verified extraction becomes a cache entry. */
class ConveyorInstaller(
    private val cacheDirectory: Path,
    private val download: (URI, Path) -> Unit = ::downloadConveyorArchive,
) {
    fun install(distribution: ConveyorDistribution, baseUrl: String): File {
        Files.createDirectories(cacheDirectory)
        val cacheName = "${distribution.version}-${distribution.platform}-${distribution.sha256.take(12)}"
        val installed = cacheDirectory.resolve(cacheName)
        val marker = installed.resolve(".archive-sha256")
        FileChannel.open(cacheDirectory.resolve("$cacheName.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                val executable = checkedArchivePath(installed, distribution.executable)
                if (Files.isRegularFile(marker) && Files.readString(marker).trim() == distribution.sha256 &&
                    Files.isRegularFile(executable) && (isWindows() || Files.isExecutable(executable))
                ) return executable.toFile()

                // An interrupted or manually damaged installation is never accepted as a successful cache hit.
                if (Files.exists(installed, NOFOLLOW_LINKS)) installed.toFile().deleteRecursively().also {
                    check(it) { "Cannot remove incomplete Conveyor cache: $installed" }
                }
                val staging = Files.createTempDirectory(cacheDirectory, "$cacheName-staging-")
                try {
                    val archive = staging.resolve(distribution.archive)
                    val uri = URI(baseUrl.trimEnd('/') + "/" + distribution.archive)
                    download(uri, archive)
                    check(sha256File(archive) == distribution.sha256) {
                        "Conveyor archive checksum mismatch for ${distribution.archive}; the download was not installed"
                    }
                    val unpacked = staging.resolve("unpacked")
                    Files.createDirectory(unpacked)
                    extractConveyorArchive(archive, unpacked)
                    val candidate = checkedArchivePath(unpacked, distribution.executable)
                    check(Files.isRegularFile(candidate)) { "Conveyor archive is missing ${distribution.executable}" }
                    if (!isWindows()) check(Files.isExecutable(candidate)) {
                        "Conveyor archive did not preserve executable permissions: ${distribution.executable}"
                    }
                    Files.writeString(unpacked.resolve(".archive-sha256"), distribution.sha256 + "\n")
                    Files.move(unpacked, installed, ATOMIC_MOVE)
                    return executable.toFile()
                } finally {
                    staging.toFile().deleteRecursively()
                }
            }
        }
    }
}

private fun downloadConveyorArchive(uri: URI, destination: Path) {
    require(uri.scheme == "https" || uri.scheme == "file") {
        "Conveyor download base must use HTTPS or file: for an offline mirror"
    }
    val connection = uri.toURL().openConnection().apply {
        connectTimeout = 30_000
        readTimeout = 120_000
    }
    connection.getInputStream().buffered().use { input ->
        Files.newOutputStream(destination).buffered().use { input.copyTo(it) }
    }
}

internal fun sha256File(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal fun checkedArchivePath(directory: Path, entry: String): Path {
    require(entry.isNotBlank() && !entry.startsWith('/') && '\\' !in entry && ':' !in entry) {
        "Invalid archive path: $entry"
    }
    val root = directory.toAbsolutePath().normalize()
    val path = root.resolve(entry).normalize()
    require(path.startsWith(root) && path != root) { "Archive entry escapes its directory: $entry" }
    var parent = path.parent
    while (parent != root) {
        require(!Files.isSymbolicLink(parent)) { "Archive entry traverses a symbolic link: $entry" }
        parent = parent.parent
    }
    return path
}

/** ZIP unix modes/symlinks matter for the macOS app; tar modes matter for the Linux launcher/JRE. */
internal fun extractConveyorArchive(archive: Path, directory: Path) {
    val links = mutableListOf<Pair<Path, String>>()
    fun target(name: String): Path = checkedArchivePath(directory, name).also {
        Files.createDirectories(it.parent)
        require(!Files.exists(it, NOFOLLOW_LINKS) || Files.isDirectory(it, NOFOLLOW_LINKS)) {
            "Duplicate archive entry: $name"
        }
    }
    fun mode(path: Path, bits: Int) {
        if (isWindows() || bits == 0) return
        val permissions = PosixFilePermission.values().filterIndexed { index, _ -> bits and (1 shl (8 - index)) != 0 }.toSet()
        Files.setPosixFilePermissions(path, permissions)
    }
    if (archive.fileName.toString().endsWith(".tar.gz")) {
        GzipCompressorInputStream(Files.newInputStream(archive).buffered()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val path = target(entry.name)
                    when {
                        entry.isDirectory -> Files.createDirectories(path)
                        entry.isSymbolicLink -> links += path to entry.linkName
                        entry.isFile -> {
                            Files.newOutputStream(path, CREATE, WRITE).buffered().use { tar.copyTo(it) }
                            mode(path, entry.mode)
                        }
                        else -> error("Unsupported Conveyor archive entry: ${entry.name}")
                    }
                }
            }
        }
    } else {
        ZipFile.builder().setPath(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val path = target(entry.name)
                when {
                    entry.isDirectory -> Files.createDirectories(path)
                    entry.isUnixSymlink -> links += path to zip.getUnixSymlink(entry)
                    else -> {
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(path, CREATE, WRITE).buffered().use { input.copyTo(it) }
                        }
                        mode(path, entry.unixMode)
                    }
                }
            }
        }
    }
    // Delay links until regular files are extracted, so a link cannot redirect a later archive entry.
    val root = directory.toAbsolutePath().normalize()
    links.forEach { (path, link) ->
        require(!link.startsWith('/') && '\\' !in link && ':' !in link) { "Invalid archive link target" }
        require(path.parent.resolve(link).normalize().startsWith(root)) { "Archive link escapes its directory" }
        checkedArchivePath(root, root.relativize(path).toString().replace(File.separatorChar, '/'))
        Files.createSymbolicLink(path, Path.of(link))
    }
}

abstract class PrepareConveyorTask : DefaultTask() {
    @get:InputFile abstract val distributionCatalog: RegularFileProperty
    @get:Internal abstract val cacheDirectory: DirectoryProperty
    @get:Input @get:Optional abstract val executableOverride: Property<String>
    @get:Input abstract val downloadBaseUrl: Property<String>
    @get:OutputFile abstract val executableDescriptor: RegularFileProperty

    init {
        group = "distribution"
        description = "Download and verify the pinned Conveyor CLI without a system installation"
        outputs.upToDateWhen { false } // The external Gradle user cache can be removed independently of build/.
    }

    @TaskAction
    fun prepare() {
        val distribution = ConveyorDistribution.read(distributionCatalog.get().asFile)
        val executable = executableOverride.orNull?.let { value ->
            File(value).absoluteFile.also {
                require(it.isFile && (isWindows() || it.canExecute())) { "conveyorExecutable must name an executable file" }
                require(it.extension.lowercase() !in setOf("bat", "cmd")) {
                    "conveyorExecutable must point to the native CLI, not a shell/batch wrapper"
                }
            }
        } ?: ConveyorInstaller(cacheDirectory.get().asFile.toPath()).install(distribution, downloadBaseUrl.get())
        val output = runCheckedProcess(ProcessSpec(
            label = "Conveyor version verification",
            arguments = listOf(executable.absolutePath, "--version"),
            outputMode = ProcessOutputMode.CAPTURE,
            timeoutMillis = 30_000,
        )).output.trim()
        check(output == "Hydraulic Conveyor ${distribution.version}") {
            "Conveyor must report the pinned version ${distribution.version}"
        }
        val descriptor = executableDescriptor.get().asFile
        descriptor.parentFile.mkdirs()
        Properties().apply {
            setProperty("executable", executable.absolutePath)
            setProperty("version", distribution.version)
            setProperty("archiveSha256", if (executableOverride.isPresent) "external-override" else distribution.sha256)
        }.also { values -> descriptor.outputStream().use { values.store(it, "Managed Conveyor tool; no signing data") } }
        logger.lifecycle("Conveyor ${distribution.version} ready (${distribution.platform})")
    }
}

/** Public adapter keeps Gradle scripts independent of the deployment package's process internals. */
fun runConveyor(executableDescriptor: File, projectDirectory: File, arguments: List<String>) {
    val executable = Properties().apply { executableDescriptor.inputStream().use(::load) }.getProperty("executable")
    require(!executable.isNullOrBlank()) { "Run prepareConveyor before invoking the packaging tool" }
    runCheckedProcess(ProcessSpec(
        label = "Conveyor desktop packaging",
        arguments = listOf(executable) + arguments,
        workingDirectory = projectDirectory,
        timeoutMillis = 1_200_000,
        environment = mapOf("CONVEYOR_AGREE_TO_LICENSE" to "1"),
    ))
}

/** A previous site is never relabelled: publish the new directory only after Conveyor has succeeded. */
fun buildConveyorSite(
    executableDescriptor: File,
    projectDirectory: File,
    configDirectory: File,
    version: String,
    buildIdentity: String,
) {
    val temporaryRoot = projectDirectory.resolve("build/conveyor").toPath()
    Files.createDirectories(temporaryRoot)
    val staging = Files.createTempDirectory(temporaryRoot, "site-staging-")
    val output = projectDirectory.resolve("output").toPath()
    val previous = temporaryRoot.resolve("previous-site")
    try {
        runConveyor(executableDescriptor, projectDirectory, listOf(
            "--console=plain", "--conf-dir=${configDirectory.absolutePath}",
            "make", "site", "--output-dir=${staging.toAbsolutePath()}", "--overwrite",
        ))
        writeReleaseArtifactManifest(staging.toFile(), "desktop-site", version, buildIdentity)
        if (Files.exists(previous, NOFOLLOW_LINKS)) check(previous.toFile().deleteRecursively()) {
            "Cannot remove the previous generated Conveyor site backup"
        }
        val hadPrevious = Files.exists(output, NOFOLLOW_LINKS)
        if (hadPrevious) Files.move(output, previous, ATOMIC_MOVE)
        try {
            Files.move(staging, output, ATOMIC_MOVE)
        } catch (failure: Exception) {
            if (hadPrevious) Files.move(previous, output, ATOMIC_MOVE)
            throw failure
        }
    } finally {
        staging.toFile().deleteRecursively()
    }
}

fun defaultConveyorConfigDirectory(): File = when {
    isWindows() -> File(System.getenv("USERPROFILE") ?: System.getProperty("user.home"), "Hydraulic/Conveyor")
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
        File(System.getProperty("user.home"), "Library/Preferences/Hydraulic/Conveyor")
    else -> File(System.getenv("XDG_CONFIG_HOME") ?: File(System.getProperty("user.home"), ".config").path, "hydraulic/conveyor")
}

/** Parse failures may quote secret source lines, so never attach their messages or causes to Gradle errors. */
fun requireConveyorSigningConfiguration(configDirectory: File, environment: Map<String, String> = System.getenv()) {
    val configured = try {
        val defaults = configDirectory.resolve("defaults.conf")
        if (!defaults.isFile || defaults.length() == 0L) false else {
            val config = ConfigFactory.parseFile(defaults, ConfigParseOptions.defaults().setAllowMissing(false))
                .resolveWith(ConfigFactory.parseMap(mapOf("env" to environment)), ConfigResolveOptions.noSystem())
            val key = config.getValue("app.signing-key")
            key.valueType() == ConfigValueType.STRING && (key.unwrapped() as String).isNotBlank()
        }
    } catch (_: Exception) {
        false
    }
    if (!configured) throw GradleException(
        "Conveyor signing configuration must contain an existing, non-empty app.signing-key in defaults.conf " +
            "(a literal, keyring reference, or an env reference). Provide -PconveyorConfigDir or " +
            "TEAMTALK_CONVEYOR_CONFIG_DIR. Release builds never create replacement signing keys; " +
            "configuration details are withheld because they may contain secrets.",
    )
}
