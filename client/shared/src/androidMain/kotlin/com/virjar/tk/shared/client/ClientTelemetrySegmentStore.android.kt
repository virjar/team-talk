package com.virjar.tk.shared.client

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal actual fun createClientTelemetrySegmentStore(
    dataDir: File,
    privateDirectories: List<String>,
): ClientTelemetrySegmentStore = AndroidClientTelemetrySegmentStore(dataDir, privateDirectories)

private class AndroidClientTelemetrySegmentStore(
    dataDir: File,
    privateDirectories: List<String>,
) : ClientTelemetrySegmentStore {
    private val dataRoot = dataDir.toPath().toAbsolutePath().normalize()
    private val privateDirectories = requireTelemetryPrivateDirectories(privateDirectories).toList()
    override val identityDirectories: List<String> = this.privateDirectories.drop(1)
    private val telemetryRoot = dataRoot.resolve(CLIENT_TELEMETRY_ROOT_DIRECTORY)
    private val processRootLock = ClientTelemetryProcessRootLocks.forRoot(telemetryRoot)
    private val namespace = this.privateDirectories.fold(dataRoot) { parent, component -> parent.resolve(component) }
    private val marker = privateAtomicTextFileStore(
        dataDir,
        this.privateDirectories,
        CLIENT_TELEMETRY_MARKER_FILE,
        CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
    )
    private val registryFile = privateAtomicTextFileStore(
        dataDir,
        listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY),
        CLIENT_TELEMETRY_REGISTRY_FILE,
        CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
    )
    private val rootLockFile: Path
    private val scanner = NioClientTelemetryNamespaceScanner(
        dataRoot = dataRoot,
        requirePrivateDirectory = ::requirePrivateDirectory,
        requirePrivateFile = ::requirePrivateFile,
        loadRegistry = ::loadRegistryLocked,
        replaceRegistry = ::replaceRegistryLocked,
        forceDirectory = ::forceTelemetryDirectory,
    )

    init {
        prepareTelemetryRoot()
        rootLockFile = prepareRootLockFile()
        withRootLock {
            ensureCurrentNamespaceLocked()
        }
    }

    @Synchronized
    override fun writeNew(fileName: String, content: String): Unit = withRootLock {
        requireSegmentFileName(fileName)
        ensureCurrentNamespaceLocked()
        val existing = readLocked(fileName)
        if (existing != null) {
            check(existing == content) { "Immutable telemetry segment collision" }
            return@withRootLock
        }
        privateAtomicTextFileStore(
            dataRoot.toFile(),
            privateDirectories,
            fileName,
            CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
        ).replaceText(
            content,
            MAX_TELEMETRY_SEGMENT_BYTES,
        )
        check(readLocked(fileName) == content) { "Telemetry segment changed during publication" }
    }

    @Synchronized
    override fun read(fileName: String): String? = withRootLock {
        requireSegmentFileName(fileName)
        readLocked(fileName)
    }

    @Synchronized
    override fun list(): List<StoredTelemetrySegmentFile> = withRootLock {
        if (!currentNamespaceExistsLocked()) return@withRootLock emptyList()
        useResourcePreservingFatalFailure(Files.newDirectoryStream(namespace)) { children ->
            children.mapNotNull { file ->
                if (file.fileName.toString() == CLIENT_TELEMETRY_MARKER_FILE) return@mapNotNull null
                requireSegmentFileName(file.fileName.toString())
                val attributes = requirePrivateFile(file, namespace)
                require(attributes.size() <= MAX_TELEMETRY_SEGMENT_BYTES) {
                    "Telemetry segment is too large"
                }
                StoredTelemetrySegmentFile(
                    file.fileName.toString(),
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                )
            }
        }
    }

    @Synchronized
    override fun delete(fileName: String): Boolean = withRootLock {
        requireSegmentFileName(fileName)
        privateAtomicTextFileStore(dataRoot.toFile(), privateDirectories, fileName).delete()
    }

    @Synchronized
    override fun maintainNamespaces(
        nowEpochMs: Long,
        cutoffEpochMs: Long,
        retentionMillis: Long,
        maxVisitedNodes: Int,
        maxDeletes: Int,
    ): TelemetryNamespaceMaintenanceResult = withRootLock {
        scanner.maintain(
            currentIdentityDirectories = identityDirectories,
            nowEpochMs = nowEpochMs,
            cutoffEpochMs = cutoffEpochMs,
            retentionMillis = retentionMillis,
            maxVisitedNodes = maxVisitedNodes,
            maxDeletes = maxDeletes,
        )
    }

    private fun readLocked(fileName: String): String? {
        if (!currentNamespaceExistsLocked()) return null
        val target = namespace.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val attributes = requirePrivateFile(target, namespace)
        require(attributes.size() <= MAX_TELEMETRY_SEGMENT_BYTES) { "Telemetry segment is too large" }
        return privateAtomicTextFileStore(dataRoot.toFile(), privateDirectories, fileName)
            .readText(MAX_TELEMETRY_SEGMENT_BYTES)
    }

    /** 注册、命名空间创建与标记刷新是一次持有 root 锁的发布操作。 */
    private fun ensureCurrentNamespaceLocked() {
        registryFile.cleanupPendingReplacement()
        val registry = loadRegistryLocked()
        val registered = registry.register(identityDirectories)
        if (registered != registry) replaceRegistryLocked(registered)
        marker.replaceText(CLIENT_TELEMETRY_MARKER_CONTENT, MAX_MARKER_BYTES)
        require(marker.readText(MAX_MARKER_BYTES) == CLIENT_TELEMETRY_MARKER_CONTENT) {
            "Telemetry namespace marker is invalid"
        }
    }

    private fun loadRegistryLocked(): ClientTelemetryNamespaceRegistry =
        registryFile.readText(MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES.toLong())
            ?.let(::decodeClientTelemetryNamespaceRegistry)
            ?: ClientTelemetryNamespaceRegistry.empty()

    private fun replaceRegistryLocked(registry: ClientTelemetryNamespaceRegistry) {
        registryFile.replaceText(
            encodeClientTelemetryNamespaceRegistry(registry),
            MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES.toLong(),
        )
    }

    /** 重新校验每一级祖先目录；一个被并发退役的精确叶子目录只会表现为一个空的假脱机区。 */
    private fun currentNamespaceExistsLocked(): Boolean {
        requireTrustedDataRoot()
        var parent = dataRoot
        privateDirectories.forEach { component ->
            val child = parent.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) return false
            requirePrivateDirectory(child, parent)
            parent = child
        }
        return true
    }

    private fun prepareRootLockFile(): Path {
        requireTrustedDataRoot()
        requirePrivateDirectory(telemetryRoot, dataRoot)
        val lockFile = telemetryRoot.resolve(ROOT_LOCK_FILE)
        if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(lockFile, FILE_ATTRIBUTE)
                forceDirectory(telemetryRoot)
            } catch (_: FileAlreadyExistsException) {
                // 竞争创建者只有在通过下方精确的私有文件校验后才被接受。
            }
        }
        requirePrivateFile(lockFile, telemetryRoot)
        return lockFile
    }

    private fun prepareTelemetryRoot() {
        requireTrustedDataRoot()
        if (!Files.exists(telemetryRoot, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(telemetryRoot, DIRECTORY_ATTRIBUTE)
                forceDirectory(dataRoot)
            } catch (_: FileAlreadyExistsException) {
                // 竞争创建者只有在通过下方精确校验后才被接受。
            }
        }
        requirePrivateDirectory(telemetryRoot, dataRoot)
    }

    private fun <T> withRootLock(action: () -> T): T = synchronized(processRootLock) {
        val before = requirePrivateFile(rootLockFile, telemetryRoot)
        useResourcePreservingFatalFailure(FileChannel.open(rootLockFile, LOCK_OPTIONS)) { channel ->
            useResourcePreservingFatalFailure(channel.lock()) {
                val after = requirePrivateFile(rootLockFile, telemetryRoot)
                require(sameTelemetryLockFile(before, after)) {
                    "Telemetry root lock changed before acquisition"
                }
                action()
            }
        }
    }

    /** Android 的应用私有数据根目录是可信的，但这里从不修改其权限或重写它。 */
    private fun requireTrustedDataRoot(): BasicFileAttributes {
        val attributes = Files.readAttributes(
            dataRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            "Telemetry data root must be a real directory"
        }
        return attributes
    }

    private fun requirePrivateDirectory(path: Path, parent: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            "Telemetry namespace component must be a real directory"
        }
        requireSameOwner(path, parent)
        require(posixPermissions(path) == DIRECTORY_PERMISSIONS) {
            "Telemetry namespace permissions are not 0700"
        }
        return attributes
    }

    private fun requirePrivateFile(path: Path, parent: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther) {
            "Telemetry payload must be a real regular file"
        }
        requireSameOwner(path, parent)
        require(posixPermissions(path) == FILE_PERMISSIONS) {
            "Telemetry payload permissions are not 0600"
        }
        require(
            (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
        ) { "Hard-linked telemetry payloads are not allowed" }
        return attributes
    }

    private fun requireSameOwner(path: Path, parent: Path) {
        require(
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) ==
                Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS),
        ) { "Telemetry path owner does not match its parent" }
    }

    private fun posixPermissions(path: Path): Set<PosixFilePermission> {
        val view = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("Telemetry store requires a POSIX filesystem")
        return view.readAttributes().permissions()
    }

    private fun forceDirectory(directory: Path) {
        useResourcePreservingFatalFailure(FileChannel.open(directory, StandardOpenOption.READ)) { channel ->
            channel.force(true)
        }
    }

    private fun forceTelemetryDirectory(directory: Path, expectedStorageIdentity: Any) {
        val parent = requireNotNull(directory.parent) { "Telemetry directory must have a parent" }
        val before = requirePrivateDirectory(directory, parent)
        require(before.fileKey() != null && before.fileKey() == expectedStorageIdentity) {
            "Telemetry directory no longer matches its secure handle"
        }
        useResourcePreservingFatalFailure(FileChannel.open(directory, DIRECTORY_FORCE_OPTIONS)) { channel ->
            channel.force(true)
        }
        val after = requirePrivateDirectory(directory, parent)
        require(after.fileKey() != null && after.fileKey() == expectedStorageIdentity) {
            "Telemetry directory changed while forcing metadata"
        }
    }

    private fun requireSegmentFileName(fileName: String) {
        require(CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(fileName)) {
            "Invalid telemetry segment file name"
        }
    }

    private companion object {
        const val ROOT_LOCK_FILE = ".telemetry-lifecycle.lock"
        const val MAX_MARKER_BYTES = 32L
        val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        val DIRECTORY_ATTRIBUTE = PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS)
        val FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)
        val LOCK_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val DIRECTORY_FORCE_OPTIONS: Set<OpenOption> =
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }
}
