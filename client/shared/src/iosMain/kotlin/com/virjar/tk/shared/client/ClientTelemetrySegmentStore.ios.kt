@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import kotlinx.cinterop.*
import platform.posix.*

internal actual fun createClientTelemetrySegmentStore(dataDir: PlatformFile, privateDirectories: List<String>): ClientTelemetrySegmentStore =
    IosClientTelemetrySegmentStore(dataDir, requireTelemetryPrivateDirectories(privateDirectories))

private class IosClientTelemetrySegmentStore(
    private val dataDir: PlatformFile,
    private val directories: List<String>,
) : ClientTelemetrySegmentStore {
    override val identityDirectories = directories.drop(1)
    private val directory = directories.fold(dataDir) { parent, name -> PlatformFile(parent, name) }
    private val marker = privateAtomicTextFileStore(
        dataDir, directories, CLIENT_TELEMETRY_MARKER_FILE, CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
    )
    private val registryFile = privateAtomicTextFileStore(
        dataDir, listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY),
        CLIENT_TELEMETRY_REGISTRY_FILE, CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
    )

    init {
        synchronized(iosStorageLock) {
            ensureCurrentNamespace()
        }
    }
    private fun store(name: String): PrivateAtomicTextFileStore {
        require(CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(name)) { "Invalid telemetry segment name" }
        return privateAtomicTextFileStore(dataDir, directories, name, CLIENT_TELEMETRY_ATOMIC_PENDING_FILE)
    }
    override fun writeNew(fileName: String, content: String): Unit = synchronized(iosStorageLock) {
        val target = store(fileName)
        ensureCurrentNamespace()
        val previous = target.readText(MAX_TELEMETRY_SEGMENT_BYTES)
        if (previous != null) {
            check(previous == content) { "Telemetry segment identity already contains different bytes" }
            return@synchronized
        }
        check(list().size < MAX_TELEMETRY_NAMESPACE_SEGMENTS) { "Telemetry spool capacity exceeded" }
        target.replaceText(content, MAX_TELEMETRY_SEGMENT_BYTES)
    }
    override fun read(fileName: String): String? = synchronized(iosStorageLock) { store(fileName).readText(MAX_TELEMETRY_SEGMENT_BYTES) }
    override fun list(): List<StoredTelemetrySegmentFile> = synchronized(iosStorageLock) {
        val files = checkNotNull(boundedEntries(directory, MAX_TELEMETRY_NAMESPACE_SEGMENTS + 3)) {
            "Telemetry namespace exceeds entry bound"
        }
        files.filter { CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(it.name) }.map { file ->
            requireIosRegularFile(file)
            check(file.length() <= MAX_TELEMETRY_SEGMENT_BYTES) { "Telemetry segment is oversized" }
            StoredTelemetrySegmentFile(file.name, file.length(), file.lastModified())
        }
    }
    override fun delete(fileName: String): Boolean = synchronized(iosStorageLock) { store(fileName).delete() }

    private fun ensureCurrentNamespace() {
        registryFile.cleanupPendingReplacement()
        val registry = loadRegistry()
        val registered = registry.register(identityDirectories)
        if (registered != registry) replaceRegistry(registered)
        iosPrivateDirectory(dataDir, directories)
        val version = marker.readText(MAX_MARKER_BYTES)
        require(version == null || version == CLIENT_TELEMETRY_MARKER_CONTENT) { "Unsupported telemetry spool version" }
        marker.cleanupPendingReplacement()
        marker.replaceText(CLIENT_TELEMETRY_MARKER_CONTENT, MAX_MARKER_BYTES)
    }

    private fun loadRegistry(): ClientTelemetryNamespaceRegistry =
        registryFile.readText(MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES.toLong())
            ?.let(::decodeClientTelemetryNamespaceRegistry)
            ?: ClientTelemetryNamespaceRegistry.empty()

    private fun replaceRegistry(registry: ClientTelemetryNamespaceRegistry) {
        registryFile.replaceText(
            encodeClientTelemetryNamespaceRegistry(registry), MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES.toLong(),
        )
    }

    override fun maintainNamespaces(
        nowEpochMs: Long,
        cutoffEpochMs: Long,
        retentionMillis: Long,
        maxVisitedNodes: Int,
        maxDeletes: Int,
    ): TelemetryNamespaceMaintenanceResult = synchronized(iosStorageLock) {
        require(nowEpochMs >= 0L && cutoffEpochMs >= 0L && retentionMillis > 0L)
        require(maxVisitedNodes >= 2 * MAX_NAMESPACE_INSPECTION_NODES && maxDeletes > 0)
        val registry = loadRegistry()
        // 为每个 identity 预留一次完整扫描和一次删除复查。持久化的 identity 键
        // 游标在重开 store 与执行删除后仍然有效；POSIX 目录 cookie 两者都做不到。
        val page = registry.page(maxVisitedNodes / (2 * MAX_NAMESPACE_INSPECTION_NODES))
        var updatedRegistry = page.registry
        val candidates = mutableListOf<StoredTelemetryNamespace>()
        var visited = 0
        for (identity in page.identities) {
            visited += NAMESPACE_DIRECTORY_NODES
            val leaf = resolveLeaf(identity) ?: continue
            if (!leaf.exists()) {
                if (identity != identityDirectories) updatedRegistry = updatedRegistry.remove(identity)
                continue
            }
            val (snapshot, inspected) = scanLeaf(leaf, identity)
            visited += inspected
            snapshot?.let(candidates::add)
        }
        val scan = StoredTelemetryNamespaceScan(candidates, visited, page.truncated)
        val cleanups = selectExpiredTelemetryNamespaceCleanups(
            identityDirectories, scan, cutoffEpochMs, candidates.size.coerceAtLeast(1),
        )
        var needsRetry = registry.cycleNeedsImmediateRetry || cleanups.size > maxDeletes
        for (cleanup in cleanups.take(maxDeletes)) {
            visited += NAMESPACE_DIRECTORY_NODES
            val leaf = resolveLeaf(cleanup.snapshot.identityDirectories)
            if (leaf == null) { needsRetry = true; continue }
            val (fresh, revisited) = scanLeaf(leaf, cleanup.snapshot.identityDirectories)
            visited += revisited
            if (fresh == cleanup.snapshot) {
                cleanup.expiredSegmentFileNames.forEach { name -> check(PlatformFile(leaf, name).delete()) }
                if (cleanup.deleteWholeNamespace) {
                    val version = PlatformFile(leaf, CLIENT_TELEMETRY_MARKER_FILE)
                    if (version.exists()) check(version.delete())
                    leaf.syncToDisk()
                    check(leaf.delete())
                    leaf.parentFile?.syncToDisk()
                    updatedRegistry = updatedRegistry.remove(cleanup.snapshot.identityDirectories)
                } else {
                    leaf.syncToDisk()
                }
            } else {
                needsRetry = true
            }
        }
        check(visited <= maxVisitedNodes) { "Telemetry root scan exceeded its node budget" }
        val discoveryDeadline = saturatingEpochAdd(nowEpochMs, retentionMillis, 0L)
        val pageDeadline = nextTelemetryNamespaceMaintenanceEpochMs(identityDirectories, scan, cutoffEpochMs, retentionMillis)
            ?.coerceAtMost(discoveryDeadline) ?: discoveryDeadline
        val cycleDeadline = registry.cycleDeadlineEpochMs?.coerceAtMost(pageDeadline) ?: pageDeadline
        val next = if (page.truncated || needsRetry) nowEpochMs else maxOf(nowEpochMs, cycleDeadline)
        updatedRegistry = updatedRegistry.copy(
            cycleDeadlineEpochMs = if (page.truncated) cycleDeadline else null,
            cycleNeedsImmediateRetry = page.truncated && needsRetry,
        )
        if (updatedRegistry != registry) replaceRegistry(updatedRegistry)
        TelemetryNamespaceMaintenanceResult(visited, page.truncated, next)
    }

    private fun resolveLeaf(identity: List<String>): PlatformFile? = try {
        iosPrivateDirectory(dataDir, listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY) + identity, create = false)
    } catch (_: IllegalArgumentException) {
        // 祖先被替换或存在符号链接并不等于命名空间缺失，必须保留在注册表中。
        null
    }

    private fun scanLeaf(leaf: PlatformFile, components: List<String>): Pair<StoredTelemetryNamespace?, Int> {
        if (!leaf.isDirectory || leaf.isSymbolicLink()) return null to 1
        val files = boundedEntries(leaf, MAX_NAMESPACE_ENTRIES) ?: return null to (MAX_NAMESPACE_ENTRIES + 1)
        if (files.any { file ->
                !file.isFile || file.isSymbolicLink() || when (file.name) {
                    CLIENT_TELEMETRY_MARKER_FILE -> file.length() > MAX_MARKER_BYTES || file.readText() != CLIENT_TELEMETRY_MARKER_CONTENT
                    CLIENT_TELEMETRY_ATOMIC_PENDING_FILE -> file.length() > MAX_TELEMETRY_SEGMENT_BYTES
                    else -> !CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(file.name) ||
                        file.length() !in 1L..MAX_TELEMETRY_SEGMENT_BYTES || telemetrySegmentCreatedAtEpochMs(file.name) == null
                }
            }
        ) return null to files.size
        val committed = files.filter { it.name != CLIENT_TELEMETRY_ATOMIC_PENDING_FILE }
        if (committed.isNotEmpty() && committed.none { it.name == CLIENT_TELEMETRY_MARKER_FILE }) return null to files.size
        if (committed.count { CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(it.name) } > MAX_TELEMETRY_NAMESPACE_SEGMENTS) return null to files.size
        // 只拥有那个精确、常规且有界的 pending 文件。先校验整个 leaf，让
        // 畸形/未知内容保持原样，包括它们潜在的恢复证据。
        files.firstOrNull { it.name == CLIENT_TELEMETRY_ATOMIC_PENDING_FILE }?.let { pending ->
            check(pending.delete())
            leaf.syncToDisk()
        }
        val entries = committed.map { file -> StoredTelemetryNamespaceEntry(file.name, file.length(), file.lastModified(), storageIdentity(file)) }
        val retentionReference = entries.fold(leaf.lastModified()) { newest, entry ->
            maxOf(newest, if (entry.fileName == CLIENT_TELEMETRY_MARKER_FILE) entry.lastModifiedEpochMs else
                minOf(checkNotNull(telemetrySegmentCreatedAtEpochMs(entry.fileName)), entry.lastModifiedEpochMs))
        }
        return StoredTelemetryNamespace(components, retentionReference, storageIdentity(leaf), entries) to files.size
    }
}

private const val MAX_MARKER_BYTES = 32L
private const val MAX_NAMESPACE_ENTRIES = MAX_TELEMETRY_NAMESPACE_SEGMENTS + 2 // marker 文件 + 固定 pending 文件
private const val NAMESPACE_DIRECTORY_NODES = 4 // root + deployment/dataset/uid
private const val MAX_NAMESPACE_INSPECTION_NODES = NAMESPACE_DIRECTORY_NODES + MAX_NAMESPACE_ENTRIES + 1

private fun boundedEntries(directory: PlatformFile, limit: Int): List<PlatformFile>? {
    val stream = checkNotNull(opendir(directory.path)) { "Cannot scan telemetry storage" }
    try {
        val files = mutableListOf<PlatformFile>()
        while (true) {
            val entry = readdir(stream) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            if (files.size == limit) return null
            files.add(PlatformFile(directory, name))
        }
        return files
    } finally { closedir(stream) }
}
private fun storageIdentity(file: PlatformFile): List<Long> = memScoped {
    val info = alloc<stat>()
    check(lstat(file.path, info.ptr) == 0)
    listOf(info.st_dev.toLong(), info.st_ino.toLong(), info.st_size, info.st_mtimespec.tv_sec, info.st_mtimespec.tv_nsec)
}
