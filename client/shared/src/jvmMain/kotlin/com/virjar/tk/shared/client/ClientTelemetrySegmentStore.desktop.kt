package com.virjar.tk.shared.client

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal actual fun createClientTelemetrySegmentStore(
    dataDir: File,
    privateDirectories: List<String>,
): ClientTelemetrySegmentStore = JvmClientTelemetrySegmentStore(dataDir, privateDirectories)

private class JvmClientTelemetrySegmentStore(
    dataDir: File,
    privateDirectories: List<String>,
) : ClientTelemetrySegmentStore {
    private val dataDirectory = JvmPrivateDataDirectory.openExisting(dataDir)
    private val privateDirectories = requireTelemetryPrivateDirectories(privateDirectories).toList()
    override val identityDirectories: List<String> = this.privateDirectories.drop(1)
    private val telemetryRootDirectories = listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY)
    private val telemetryRoot = dataDirectory.ensureDirectory(telemetryRootDirectories)
    private val processRootLock = ClientTelemetryProcessRootLocks.forRoot(telemetryRoot)
    private val rootLockFile = dataDirectory.preparePrivateFile(telemetryRootDirectories, ROOT_LOCK_FILE).toPath()
    private val marker = dataDirectory.atomicTextFile(
        this.privateDirectories,
        CLIENT_TELEMETRY_MARKER_FILE,
        CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
    )
    private val registryFile = dataDirectory.atomicTextFile(
        telemetryRootDirectories,
        CLIENT_TELEMETRY_REGISTRY_FILE,
        CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
    )
    private val scanner = NioClientTelemetryNamespaceScanner(
        dataRoot = dataDirectory.root,
        requirePrivateDirectory = ::requirePrivateDirectory,
        requirePrivateFile = ::requirePrivateFile,
        loadRegistry = ::loadRegistryLocked,
        replaceRegistry = ::replaceRegistryLocked,
        forceDirectory = dataDirectory.security()::forceDirectoryDurably,
    )

    init {
        withRootLock {
            ensureCurrentNamespaceLocked()
        }
    }

    @Synchronized
    override fun writeNew(fileName: String, content: String): Unit = withRootLock {
        requireSegmentFileName(fileName)
        ensureCurrentNamespaceLocked()
        val files = listFileNamesLocked()
        if (fileName in files) {
            check(readLocked(fileName) == content) { "Immutable telemetry segment collision" }
            return@withRootLock
        }
        dataDirectory.atomicTextFile(
            privateDirectories,
            fileName,
            CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
        ).replaceText(
            content = content,
            maxBytes = MAX_TELEMETRY_SEGMENT_BYTES,
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
        listFileNamesLocked().map { fileName ->
            val file = dataDirectory.requirePrivateFile(privateDirectories, fileName)
            StoredTelemetrySegmentFile(fileName, file.length(), file.lastModified())
        }
    }

    @Synchronized
    override fun delete(fileName: String): Boolean = withRootLock {
        requireSegmentFileName(fileName)
        dataDirectory.atomicTextFile(privateDirectories, fileName).delete()
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
        if (fileName !in listFileNamesLocked()) return null
        return dataDirectory.atomicTextFile(privateDirectories, fileName).readText(MAX_TELEMETRY_SEGMENT_BYTES)
    }

    private fun listFileNamesLocked(): Set<String> =
        dataDirectory.listPrivateFileNames(privateDirectories)
            .asSequence()
            .filter { it != CLIENT_TELEMETRY_MARKER_FILE }
            .onEach(::requireSegmentFileName)
            .toCollection(linkedSetOf())

    /** 注册、命名空间创建与标记刷新是一次持有 root 锁的发布操作。 */
    private fun ensureCurrentNamespaceLocked() {
        registryFile.cleanupPendingReplacement()
        val registry = loadRegistryLocked()
        val registered = registry.register(identityDirectories)
        if (registered != registry) replaceRegistryLocked(registered)
        dataDirectory.ensureDirectory(*privateDirectories.toTypedArray())
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

    private fun <T> withRootLock(action: () -> T): T = synchronized(processRootLock) {
        val before = dataDirectory.security().requirePrivateFile(rootLockFile)
        useResourcePreservingFatalFailure(FileChannel.open(rootLockFile, LOCK_OPTIONS)) { channel ->
            useResourcePreservingFatalFailure(channel.lock()) {
                val after = dataDirectory.security().requirePrivateFile(rootLockFile)
                require(sameTelemetryLockFile(before, after)) {
                    "Telemetry root lock changed before acquisition"
                }
                action()
            }
        }
    }

    private fun requirePrivateDirectory(path: Path, @Suppress("UNUSED_PARAMETER") parent: Path): BasicFileAttributes {
        dataDirectory.security().requirePrivateDirectory(path)
        return basicAttributes(path)
    }

    private fun requirePrivateFile(path: Path, @Suppress("UNUSED_PARAMETER") parent: Path): BasicFileAttributes =
        dataDirectory.security().requirePrivateFile(path)

    private fun requireSegmentFileName(fileName: String) {
        require(CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(fileName)) {
            "Invalid telemetry segment file name"
        }
    }

    private companion object {
        const val ROOT_LOCK_FILE = ".telemetry-lifecycle.lock"
        const val MAX_MARKER_BYTES = 32L
        val LOCK_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    }
}
