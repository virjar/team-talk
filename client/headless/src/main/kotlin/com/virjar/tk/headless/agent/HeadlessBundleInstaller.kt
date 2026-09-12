package com.virjar.tk.headless.agent

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Properties

/** User-owned portable installation. Agent credentials and data directories are never installation payload. */
object HeadlessBundleInstaller {
    data class BundleFacts(
        val directory: File,
        val version: String,
        val buildIdentity: String,
        val releaseBuildNumber: Long,
        val protocolMajor: Int,
        val protocolMinor: Int,
        val minimumJavaVersion: Int,
        val checksumSha256: String,
    )
    data class Result(val command: String, val prefix: File, val bundle: BundleFacts?)

    fun execute(command: String, args: List<String>, currentBundle: File?): Result {
        require(command in setOf("install-bundle", "upgrade-bundle", "uninstall-bundle")) { "Unknown bundle command" }
        require(!System.getProperty("os.name").startsWith("Windows", true)) { "Bundle installation requires a POSIX system" }
        require(args.size == 2 && args[0] == "--prefix" && args[1].isNotBlank()) { "Usage: $command --prefix <dedicated-directory>" }
        val prefix = installPrefix(File(args[1]).toPath())
        val source = if (command == "uninstall-bundle") null else verifyBundle(requireNotNull(currentBundle) {
            "Run this command from an extracted headless distribution"
        })
        if (command == "uninstall-bundle") {
            require(currentBundle == null || !currentBundle.toPath().toRealPath().startsWith(prefix)) {
                "Run uninstall-bundle from an external extracted distribution; this process is using the installation"
            }
        }
        if (!Files.exists(prefix, NOFOLLOW_LINKS)) {
            require(command == "install-bundle") { "No headless installation exists at this prefix" }
            Files.createDirectory(prefix, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY))
            writeFile(prefix.resolve(MARKER), MARKER_BYTES)
        }
        requireMarker(prefix)
        val operator = prefix.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        require(Files.getOwner(prefix, NOFOLLOW_LINKS) == operator) { "Run bundle management as the installation owner" }
        requireRootEntries(prefix)
        if (command == "install-bundle") require(!Files.exists(prefix.resolve("current"), NOFOLLOW_LINKS)) {
            "Already installed; use upgrade-bundle"
        }
        return lock(prefix.resolve(MUTATION_LOCK), shared = false, create = command == "install-bundle").use {
            if (command == "install-bundle") completeBase(prefix)
            val installed = inspectInstallation(prefix, requireCurrent = command != "install-bundle")
            when (command) {
                "uninstall-bundle" -> {
                    require(!Files.exists(prefix.resolve(STAGING), NOFOLLOW_LINKS)) { "Incomplete staging exists; finish or retry the bundle upgrade before uninstalling" }
                    val leases = mutableListOf<AutoCloseable>()
                    try {
                        installed.forEach { leases += lock(it.directory.toPath().parent.resolve(RUNTIME_LOCK), shared = false) }
                        // Revalidate before deleting: no unknown files, changed wrappers or modified payload are removed.
                        inspectInstallation(prefix, requireCurrent = true)
                        deleteInstallation(prefix)
                    } finally { leases.asReversed().forEach(AutoCloseable::close) }
                    Result(command, prefix.toFile(), null)
                }
                else -> {
                    if (command == "install-bundle") require(!Files.exists(prefix.resolve("current"), NOFOLLOW_LINKS)) {
                        "Already installed; use upgrade-bundle"
                    }
                    val incoming = requireNotNull(source)
                    require(!incoming.directory.toPath().startsWith(prefix.resolve(STAGING))) { "Staging cannot be an installation source" }
                    recoverStaging(prefix)
                    val versionRoot = prefix.resolve("versions").resolve(incoming.checksumSha256)
                    if (!Files.exists(versionRoot, NOFOLLOW_LINKS)) stageVersion(prefix, incoming, versionRoot)
                    val verified = inspectVersion(versionRoot)
                    require(verified.checksumSha256 == incoming.checksumSha256) { "Installed version identity differs from source" }
                    val next = prefix.resolve(NEXT_CURRENT)
                    Files.deleteIfExists(next)
                    Files.createSymbolicLink(next, Path.of("versions", incoming.checksumSha256, "bundle"))
                    Files.move(next, prefix.resolve("current"), ATOMIC_MOVE, REPLACE_EXISTING)
                    forceDirectory(prefix)
                    Result(command, prefix.toFile(), verified)
                }
            }
        }
    }

    /** Also used by offline doctor. Checks every byte and rejects unlisted files and symlinks. */
    fun verifyBundle(bundle: File): BundleFacts {
        val root = bundle.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) { "Bundle root must be a real directory" }
        val checksums = readBounded(root.resolve(SUMS), MAX_MANIFEST_BYTES)
        val expected = parseChecksums(checksums)
        require(expected.keys.containsAll(REQUIRED_FILES) && expected.keys.any { it.startsWith("lib/") && it.endsWith(".jar") }) {
            "Incomplete headless distribution inventory"
        }
        requireInventory(root, expected.keys + SUMS)
        expected.forEach { (name, digest) -> require(sha256(root.resolve(name)) == digest) { "Bundle checksum mismatch: $name" } }
        val properties = Properties().apply { readBounded(root.resolve(MANIFEST), 65_536).inputStream().use(::load) }
        fun property(name: String): String = properties.getProperty(name)?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: error("Missing or invalid bundle property: $name")
        require(property("artifactType") == "headless-distribution") { "Not a headless distribution" }
        val version = property("version")
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) { "Invalid bundle version" }
        val identity = property("buildIdentity")
        require(Regex("${Regex.escape(version)}\\+[0-9a-f]{40}(\\.dirty)?").matches(identity)) { "Invalid bundle build identity" }
        val build = property("releaseBuildNumber").toLongOrNull()?.takeIf { it >= 0 } ?: error("Invalid release build number")
        val major = property("protocolMajor").toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid protocol major")
        val minor = property("protocolMinor").toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid protocol minor")
        val java = property("minimumJavaVersion").toIntOrNull()?.takeIf { it >= 21 } ?: error("Invalid minimum Java version")
        return BundleFacts(root.toRealPath().toFile(), version, identity, build, major, minor, java, digest(checksums))
    }

    /** Resolves a verified managed installation for a stable service launcher; unpacked bundles return null. */
    fun managedPrefix(currentBundle: File): File? {
        val bundle = currentBundle.toPath().toRealPath()
        val prefix = installedPrefix(bundle) ?: return null
        requireMarker(prefix)
        inspectVersion(bundle.parent)
        return prefix.toFile()
    }

    /** Business entry points hold this shared version lease until agent/CLI/MCP termination. */
    fun acquireRuntimeLease(currentBundle: File?): AutoCloseable {
        if (currentBundle == null) return AutoCloseable { }
        val bundle = currentBundle.toPath().toRealPath()
        val prefix = installedPrefix(bundle) ?: return AutoCloseable { }
        requireMarker(prefix)
        // An uninstall cannot pass the version-lock check while a new process is acquiring its lease.
        return lock(prefix.resolve(MUTATION_LOCK), shared = true).use {
            require(Files.isDirectory(bundle, NOFOLLOW_LINKS)) { "The installed bundle no longer exists" }
            lock(bundle.parent.resolve(RUNTIME_LOCK), shared = true)
        }
    }

    private fun installedPrefix(bundle: Path): Path? {
        val version = bundle.parent
        val versions = version?.parent
        val prefix = versions?.parent
        if (bundle.fileName.toString() != "bundle" || versions?.fileName.toString() != "versions" ||
            !HASH.matches(version.fileName.toString()) || prefix == null || !Files.exists(prefix.resolve(MARKER), NOFOLLOW_LINKS)) {
            return null
        }
        return prefix
    }

    private fun completeBase(prefix: Path) {
        requireRootEntries(prefix)
        for (name in listOf("bin", "versions")) {
            val path = prefix.resolve(name)
            if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
            require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Installation directory is not a real directory: $name" }
        }
        val known = ENTRIES.toSet()
        Files.list(prefix.resolve("bin")).use { entries -> require(entries.allMatch { it.fileName.toString() in known }) { "Unknown installation launcher" } }
        ENTRIES.forEach { name ->
            val target = prefix.resolve("bin/$name")
            val bytes = wrapper(name)
            if (!Files.exists(target, NOFOLLOW_LINKS)) writeFile(target, bytes, executable = true)
            require(readBounded(target, 4096).contentEquals(bytes)) { "Modified installation launcher: $name" }
        }
        forceDirectory(prefix)
    }

    private fun inspectInstallation(prefix: Path, requireCurrent: Boolean): List<BundleFacts> {
        requireMarker(prefix)
        requireRootEntries(prefix)
        requireRegular(prefix.resolve(MUTATION_LOCK))
        require(Files.size(prefix.resolve(MUTATION_LOCK)) == 0L) { "Modified installation lock file" }
        require(Files.isDirectory(prefix.resolve("versions"), NOFOLLOW_LINKS)) { "Missing installed versions directory" }
        requireInventory(prefix.resolve("bin"), ENTRIES.toSet())
        ENTRIES.forEach { require(readBounded(prefix.resolve("bin/$it"), 4096).contentEquals(wrapper(it))) { "Modified installation launcher: $it" } }
        val versions = Files.list(prefix.resolve("versions")).use { paths -> paths.toList().map(::inspectVersion) }
        for (name in listOf("current", NEXT_CURRENT)) {
            val link = prefix.resolve(name)
            if (!Files.exists(link, NOFOLLOW_LINKS)) {
                require(name != "current" || !requireCurrent) { "Installation has no current bundle; retry install-bundle" }
                continue
            }
            require(Files.isSymbolicLink(link)) { "Installation entry is not a managed link: $name" }
            val target = Files.readSymbolicLink(link).toString()
            require(versions.any { target == "versions/${it.checksumSha256}/bundle" }) { "Installation link points outside a verified version" }
        }
        return versions
    }

    private fun inspectVersion(root: Path): BundleFacts {
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && HASH.matches(root.fileName.toString())) { "Unknown installed version" }
        val entries = Files.list(root).use { it.map { path -> path.fileName.toString() }.toList().toSet() }
        require(entries == setOf("bundle", PLAN, RUNTIME_LOCK)) { "Unknown or incomplete installed version files" }
        requireRegular(root.resolve(RUNTIME_LOCK))
        require(Files.size(root.resolve(RUNTIME_LOCK)) == 0L) { "Modified runtime lock file" }
        val facts = verifyBundle(root.resolve("bundle").toFile())
        require(facts.checksumSha256 == root.fileName.toString() &&
            readBounded(root.resolve(PLAN), MAX_MANIFEST_BYTES).contentEquals(readBounded(root.resolve("bundle/$SUMS"), MAX_MANIFEST_BYTES))) {
            "Installed version receipt differs from payload"
        }
        return facts
    }

    private fun stageVersion(prefix: Path, source: BundleFacts, destination: Path) {
        val stage = prefix.resolve(STAGING)
        Files.createDirectory(stage)
        val sums = readBounded(source.directory.toPath().resolve(SUMS), MAX_MANIFEST_BYTES)
        writeFile(stage.resolve(PLAN), sums)
        writeFile(stage.resolve(RUNTIME_LOCK), byteArrayOf())
        val payload = Files.createDirectory(stage.resolve("bundle"))
        val names = parseChecksums(sums).keys + SUMS
        for (name in names.sorted()) {
            val target = payload.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(source.directory.toPath().resolve(name), target, NOFOLLOW_LINKS)
            requireRegular(target)
            Files.setPosixFilePermissions(target, if (name in ENTRIES.map { "bin/$it" }) EXECUTABLE else PRIVATE_FILE)
            FileChannel.open(target, WRITE).use { it.force(true) }
        }
        val verified = verifyBundle(payload.toFile())
        require(verified.checksumSha256 == source.checksumSha256) { "Source distribution changed while copying" }
        Files.walk(payload).use { paths ->
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted(Comparator.reverseOrder()).forEach(::forceDirectory)
        }
        forceDirectory(stage)
        Files.move(stage, destination, ATOMIC_MOVE)
        forceDirectory(destination.parent)
    }

    /** Only a managed staging plan may be discarded; published immutable versions are never upgrade garbage. */
    private fun recoverStaging(prefix: Path) {
        val stage = prefix.resolve(STAGING)
        if (!Files.exists(stage, NOFOLLOW_LINKS)) return
        require(Files.isDirectory(stage, NOFOLLOW_LINKS)) { "Invalid staging directory" }
        val entries = Files.list(stage).use { it.toList() }
        if (entries.isEmpty()) { Files.delete(stage); return }
        val planned = parseChecksums(readBounded(stage.resolve(PLAN), MAX_MANIFEST_BYTES))
        val permitted = planned.keys.map { "bundle/$it" }.toSet() + "bundle/$SUMS" + PLAN + RUNTIME_LOCK
        requireInventory(stage, permitted, allowMissing = true)
        deleteTree(stage)
    }

    private fun installPrefix(requested: Path): Path {
        val absolute = requested.toAbsolutePath().normalize()
        val parent = requireNotNull(absolute.parent) { "Filesystem root cannot be an installation prefix" }
        require(Files.isDirectory(parent)) { "Installation parent directory must already exist" }
        val prefix = parent.toRealPath().resolve(absolute.fileName)
        require(!Files.isSymbolicLink(prefix)) { "Installation prefix cannot be a symlink" }
        val declaredHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val home = if (Files.exists(declaredHome)) declaredHome.toRealPath() else declaredHome
        val reserved = setOf("/", "/bin", "/sbin", "/etc", "/var", "/var/lib", "/usr", "/usr/local", "/opt",
            "/home", "/Users", "/root", "/tmp", "/private", "/private/tmp", "/private/var", "/Library", "/Applications")
        require(prefix != home && prefix.toString() !in reserved && prefix.parent != prefix.root) { "Use a dedicated installation leaf, not a home or system directory" }
        return prefix
    }

    private fun requireMarker(prefix: Path) {
        require(Files.isDirectory(prefix, NOFOLLOW_LINKS)) { "Installation prefix is not a directory" }
        require(readBounded(prefix.resolve(MARKER), 256).contentEquals(MARKER_BYTES)) { "Prefix is not a managed headless installation" }
    }
    private fun requireRootEntries(prefix: Path) {
        val known = setOf(MARKER, MUTATION_LOCK, "bin", "versions", "current", NEXT_CURRENT, STAGING)
        Files.list(prefix).use { paths -> require(paths.allMatch { it.fileName.toString() in known }) { "Unknown files in installation prefix; nothing was removed" } }
    }
    private fun requireInventory(root: Path, expected: Set<String>, allowMissing: Boolean = false) {
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Missing payload directory" }
        val directories = expected.flatMap { name -> name.split('/').dropLast(1).runningFold("") { parent, part -> if (parent.isEmpty()) part else "$parent/$part" }.drop(1) }.toSet()
        val actual = mutableSetOf<String>()
        var count = 0
        Files.walk(root).use { paths -> paths.forEach { path ->
            require(++count <= MAX_FILES * 4) { "Bundle inventory is too large" }
            if (path != root) {
                val relative = root.relativize(path).joinToString("/")
                require(!Files.isSymbolicLink(path)) { "Symlink in managed payload: $relative" }
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) require(relative in directories) { "Unknown directory in payload: $relative" }
                else { requireRegular(path); require(relative in expected) { "Unknown file in payload: $relative" }; actual += relative }
            }
        } }
        require(allowMissing || actual == expected) { "Bundle payload is incomplete" }
    }
    private fun parseChecksums(bytes: ByteArray): Map<String, String> {
        val text = bytes.toString(Charsets.UTF_8)
        require(text.endsWith('\n') && '\r' !in text && text.length <= MAX_MANIFEST_BYTES) { "Invalid checksum manifest" }
        val result = linkedMapOf<String, String>()
        text.dropLast(1).split('\n').forEach { line ->
            val match = Regex("([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._/+\\-]*)").matchEntire(line)
                ?: error("Invalid checksum entry")
            val path = match.groupValues[2]
            require(path.length <= 512 && path != SUMS && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe checksum path" }
            require(result.put(path, match.groupValues[1]) == null && result.size <= MAX_FILES) { "Duplicate or excessive checksum entries" }
        }
        require(result.isNotEmpty() && result.keys.toList() == result.keys.sorted()) { "Checksum entries must be sorted" }
        return result
    }
    private fun requireRegular(path: Path) { require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Expected a regular managed file: ${path.fileName}" } }
    private fun readBounded(path: Path, maximum: Int): ByteArray {
        requireRegular(path)
        require(Files.size(path) <= maximum) { "Managed metadata is too large: ${path.fileName}" }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { input -> input.readNBytes(maximum + 1).also { require(it.size <= maximum) } }
    }
    private fun sha256(path: Path): String {
        requireRegular(path)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun writeFile(path: Path, bytes: ByteArray, executable: Boolean = false) {
        FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
            val data = java.nio.ByteBuffer.wrap(bytes)
            while (data.hasRemaining()) channel.write(data)
            channel.force(true)
        }
        Files.setPosixFilePermissions(path, if (executable) EXECUTABLE else PRIVATE_FILE)
    }
    private fun forceDirectory(path: Path) { FileChannel.open(path, READ).use { it.force(true) } }
    private fun lock(path: Path, shared: Boolean, create: Boolean = false): AutoCloseable {
        if (Files.exists(path, NOFOLLOW_LINKS)) requireRegular(path)
        val options: Array<java.nio.file.OpenOption> = when {
            create -> arrayOf(READ, WRITE, CREATE, NOFOLLOW_LINKS)
            shared -> arrayOf(READ, NOFOLLOW_LINKS)
            else -> arrayOf(READ, WRITE, NOFOLLOW_LINKS)
        }
        val channel = FileChannel.open(path, *options)
        val acquired: FileLock = try {
            channel.tryLock(0L, Long.MAX_VALUE, shared) ?: error("Installation is in use; stop its processes before this operation")
        } catch (failure: Throwable) {
            channel.close()
            if (failure is OverlappingFileLockException) error("Installation is in use; stop its processes before this operation")
            throw failure
        }
        return AutoCloseable { try { acquired.release() } finally { channel.close() } }
    }
    private fun deleteInstallation(prefix: Path) {
        // Marker remains until all managed payload has gone, so a concurrent launcher cannot mistake it for an unpacked bundle.
        for (name in listOf("current", NEXT_CURRENT, "bin", "versions", MUTATION_LOCK)) {
            val path = prefix.resolve(name)
            if (Files.exists(path, NOFOLLOW_LINKS)) deleteTree(path)
        }
        Files.delete(prefix.resolve(MARKER))
        Files.delete(prefix)
    }
    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    private fun wrapper(entry: String) = """#!/bin/sh
set -eu
INSTALL_ROOT=${'$'}(CDPATH= cd -P -- "${'$'}(dirname -- "${'$'}0")/.." && pwd -P)
exec "${'$'}INSTALL_ROOT/current/bin/$entry" "${'$'}@"
""".toByteArray(Charsets.UTF_8)

    private const val MANIFEST = "teamtalk-release.properties"
    private const val SUMS = "SHA256SUMS"
    private const val MARKER = ".teamtalk-headless-install"
    private const val MUTATION_LOCK = ".install.lock"
    private const val RUNTIME_LOCK = ".runtime.lock"
    private const val STAGING = ".staging"
    private const val PLAN = ".bundle-checksums"
    private const val NEXT_CURRENT = ".current-next"
    private const val MAX_MANIFEST_BYTES = 1_048_576
    private const val MAX_FILES = 4096
    private val ENTRIES = listOf("tt-agent", "tt", "tt-mcp")
    private val REQUIRED_FILES = setOf(MANIFEST, "LICENSE") + ENTRIES.flatMap { listOf("bin/$it", "bin/$it.bat") }
    private val HASH = Regex("[0-9a-f]{64}")
    private val MARKER_BYTES = "teamtalk-headless-install=1\n".toByteArray(Charsets.UTF_8)
    private val PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------")
    private val PRIVATE_FILE = PosixFilePermissions.fromString("rw-------")
    private val EXECUTABLE = PRIVATE_DIRECTORY
}
