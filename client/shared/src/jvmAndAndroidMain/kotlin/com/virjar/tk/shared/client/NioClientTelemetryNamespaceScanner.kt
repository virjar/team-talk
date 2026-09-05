package com.virjar.tk.shared.client

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** JVM 文件锁在同一进程内拒绝重叠获取，而不是等待它们。 */
internal object ClientTelemetryProcessRootLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forRoot(root: Path): Any = locks.computeIfAbsent(root.toAbsolutePath().normalize().toString()) { Any() }
}

/**
 * 注册表驱动、固定深度的客户端遥测命名空间维护。
 *
 * 注册表是候选者的唯一来源：任意根条目绝不被遍历。一趟会预留每个选定叶子的最坏情况成本，因此
 * 每页要么推进其稳定游标，要么完成当前循环。只有在文件 provider 提供 [SecureDirectoryStream]
 * 时才可用跨命名空间删除；刻意没有基于路径名的回退。
 */
internal class NioClientTelemetryNamespaceScanner(
    dataRoot: Path,
    private val requirePrivateDirectory: (path: Path, parent: Path) -> BasicFileAttributes,
    private val requirePrivateFile: (path: Path, parent: Path) -> BasicFileAttributes,
    private val loadRegistry: () -> ClientTelemetryNamespaceRegistry,
    private val replaceRegistry: (ClientTelemetryNamespaceRegistry) -> Unit,
    private val forceDirectory: (path: Path, expectedStorageIdentity: Any) -> Unit,
    private val storageIdentity: (BasicFileAttributes) -> Any? = { attributes -> attributes.fileKey() },
    private val openRoot: (Path) -> DirectoryStream<Path> = { path -> Files.newDirectoryStream(path) },
    private val afterSnapshotComparisonBeforeDelete: (Path) -> Unit = {},
) {
    private val dataRoot = dataRoot.toAbsolutePath().normalize()
    private val telemetryRoot = this.dataRoot.resolve(CLIENT_TELEMETRY_ROOT_DIRECTORY).normalize()

    fun scan(maxVisitedNodes: Int): StoredTelemetryNamespaceScan {
        val registry = loadRegistry()
        val inspected = inspectRegistryPage(registry, maxVisitedNodes, protectedIdentityDirectories = null)
            ?: return StoredTelemetryNamespaceScan(emptyList(), visitedNodes = 0, truncated = false)
        if (inspected.registry != registry) replaceRegistry(inspected.registry)
        return StoredTelemetryNamespaceScan(
            namespaces = inspected.namespaces,
            visitedNodes = inspected.visitedNodes,
            truncated = inspected.truncated,
        )
    }

    fun maintain(
        currentIdentityDirectories: List<String>,
        nowEpochMs: Long,
        cutoffEpochMs: Long,
        retentionMillis: Long,
        maxVisitedNodes: Int,
        maxDeletes: Int,
    ): TelemetryNamespaceMaintenanceResult {
        require(isTelemetryIdentityDirectories(currentIdentityDirectories))
        require(nowEpochMs >= 0L && cutoffEpochMs >= 0L && retentionMillis > 0L)
        require(maxDeletes > 0)
        val registry = loadRegistry()
        val inspected = inspectRegistryPage(
            registry,
            maxVisitedNodes,
            protectedIdentityDirectories = currentIdentityDirectories,
        )
            ?: return TelemetryNamespaceMaintenanceResult(
                visitedNodes = 0,
                truncated = false,
                nextMaintenanceEpochMs = if (
                    registry.cursor != null || registry.cycleNeedsImmediateRetry
                ) {
                    nowEpochMs
                } else {
                    registry.cycleDeadlineEpochMs
                        ?.coerceAtLeast(nowEpochMs)
                        ?.coerceAtMost(saturatingEpochAdd(nowEpochMs, retentionMillis, 0L))
                        ?: saturatingEpochAdd(nowEpochMs, retentionMillis, 0L)
                },
            )
        val scan = StoredTelemetryNamespaceScan(
            namespaces = inspected.namespaces,
            visitedNodes = inspected.visitedNodes,
            truncated = inspected.truncated,
        )
        val cleanups = selectExpiredTelemetryNamespaceCleanups(
            currentIdentityDirectories = currentIdentityDirectories,
            scan = scan,
            cutoffEpochMs = cutoffEpochMs,
            maxDeletes = scan.namespaces.size.coerceAtLeast(1),
        ).filter { cleanup -> cleanup.snapshot.hasStableDeletionIdentities() }
            .take(maxDeletes)
        var updatedRegistry = inspected.registry
        var changedSnapshots = 0
        cleanups.forEach { cleanup ->
            if (deleteIfUnchanged(cleanup)) {
                if (cleanup.deleteWholeNamespace) {
                    updatedRegistry = updatedRegistry.remove(cleanup.snapshot.identityDirectories)
                }
            } else {
                changedSnapshots++
            }
        }

        val discoveryDeadline = saturatingEpochAdd(nowEpochMs, retentionMillis, 0L)
        val pageDeadline = nextTelemetryNamespaceMaintenanceEpochMs(
            currentIdentityDirectories = currentIdentityDirectories,
            scan = scan,
            cutoffEpochMs = cutoffEpochMs,
            retentionMillis = retentionMillis,
        )?.coerceAtMost(discoveryDeadline) ?: discoveryDeadline
        val cycleDeadline = registry.cycleDeadlineEpochMs?.coerceAtMost(pageDeadline) ?: pageDeadline
        val cycleNeedsRetry = registry.cycleNeedsImmediateRetry ||
            cleanups.size == maxDeletes ||
            changedSnapshots > 0 ||
            inspected.needsImmediateRetry
        val nextMaintenanceEpochMs: Long
        updatedRegistry = if (inspected.truncated) {
            nextMaintenanceEpochMs = nowEpochMs
            updatedRegistry.copy(
                cycleDeadlineEpochMs = cycleDeadline,
                cycleNeedsImmediateRetry = cycleNeedsRetry,
            )
        } else {
            nextMaintenanceEpochMs = if (cycleNeedsRetry) nowEpochMs else maxOf(nowEpochMs, cycleDeadline)
            updatedRegistry.copy(
                cursor = null,
                cycleDeadlineEpochMs = null,
                cycleNeedsImmediateRetry = false,
            )
        }
        if (updatedRegistry != registry) replaceRegistry(updatedRegistry)
        return TelemetryNamespaceMaintenanceResult(
            visitedNodes = inspected.visitedNodes,
            truncated = inspected.truncated,
            nextMaintenanceEpochMs = nextMaintenanceEpochMs,
        )
    }

    private fun deleteIfUnchanged(cleanup: TelemetryNamespaceCleanup): Boolean {
        val namespace = cleanup.snapshot
        if (!validCleanup(cleanup)) return false
        val firstInspection = withSecureTelemetryRoot { root ->
            withIdentity(root, namespace.identityDirectories) { handles ->
                scanLeaf(
                    handles,
                    namespace.identityDirectories,
                    NodeBudget(MAX_NODES_PER_IDENTITY),
                )
            }
        }.readyIdentityValue() ?: return false
        val firstRead = (firstInspection as? LeafInspection.Valid)?.namespace ?: return false
        if (firstRead != namespace) return false

        afterSnapshotComparisonBeforeDelete(resolveIdentity(namespace.identityDirectories))

        val deleted = withSecureTelemetryRoot { root ->
            withIdentity(root, namespace.identityDirectories) { handles ->
                val current = (scanLeaf(
                    handles,
                    namespace.identityDirectories,
                    NodeBudget(MAX_NODES_PER_IDENTITY),
                ) as? LeafInspection.Valid)?.namespace
                if (current != namespace || !current.hasStableDeletionIdentities()) {
                    false
                } else {
                    deleteSnapshotEntries(handles, cleanup)
                }
            }
        }.readyIdentityValue() ?: return false
        if (!deleted) return false
        return true
    }

    private fun inspectRegistryPage(
        registry: ClientTelemetryNamespaceRegistry,
        maxVisitedNodes: Int,
        protectedIdentityDirectories: List<String>?,
    ): RegistryPageInspection? {
        require(maxVisitedNodes >= MAX_NODES_PER_IDENTITY) {
            "Telemetry root scan budget cannot inspect one bounded namespace"
        }
        val page = registry.page(maxVisitedNodes / MAX_NODES_PER_IDENTITY)
        if (page.identities.isEmpty()) {
            return RegistryPageInspection(
                registry = page.registry,
                namespaces = emptyList(),
                visitedNodes = 0,
                truncated = false,
                needsImmediateRetry = false,
            )
        }
        val budget = NodeBudget(maxVisitedNodes)
        val retired = mutableListOf<List<String>>()
        var needsImmediateRetry = false
        val access = withSecureTelemetryRoot { root ->
            page.identities.mapNotNull { identity ->
                repeat(IDENTITY_DIRECTORY_DEPTH) {
                    check(budget.visit()) { "Reserved telemetry namespace budget was exhausted" }
                }
                when (val opened = withIdentity(root, identity) { handles ->
                    when (val leaf = scanLeaf(
                        handles,
                        identity,
                        budget,
                    )) {
                        is LeafInspection.Valid -> leaf.namespace
                        LeafInspection.EmptyMarkerless -> {
                            if (identity != protectedIdentityDirectories &&
                                handles.deleteLeafAndCompactEmptyParents()
                            ) {
                                retired.add(identity)
                            } else if (identity != protectedIdentityDirectories) {
                                needsImmediateRetry = true
                            }
                            null
                        }
                        LeafInspection.Invalid -> null
                        LeafInspection.Retry -> {
                            needsImmediateRetry = true
                            null
                        }
                    }
                }) {
                    is IdentityAccess.Ready -> opened.value
                    IdentityAccess.Missing -> {
                        if (identity != protectedIdentityDirectories) retired.add(identity)
                        null
                    }
                    IdentityAccess.Invalid -> null
                    IdentityAccess.Retry -> {
                        needsImmediateRetry = true
                        null
                    }
                }
            }
        }
        if (access !is SecureRootAccess.Ready) return null
        check(budget.visited <= maxVisitedNodes) { "Telemetry root scan exceeded its node budget" }
        return RegistryPageInspection(
            registry = retired.fold(page.registry) { current, identity -> current.remove(identity) },
            namespaces = access.value,
            visitedNodes = budget.visited,
            truncated = page.truncated,
            needsImmediateRetry = needsImmediateRetry,
        )
    }

    private fun validCleanup(cleanup: TelemetryNamespaceCleanup): Boolean {
        val namespace = cleanup.snapshot
        if (!isTelemetryIdentityDirectories(namespace.identityDirectories)) return false
        val entryNames = namespace.entries.map(StoredTelemetryNamespaceEntry::fileName)
        if (entryNames.distinct().size != entryNames.size ||
            entryNames.count { it == CLIENT_TELEMETRY_MARKER_FILE } != 1 ||
            entryNames.any { name ->
                name != CLIENT_TELEMETRY_MARKER_FILE && !CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(name)
            }
        ) {
            return false
        }
        val snapshotSegments = entryNames.filter(CLIENT_TELEMETRY_SEGMENT_FILE_REGEX::matches).toSet()
        val expiredSegments = cleanup.expiredSegmentFileNames.toSet()
        return expiredSegments.size == cleanup.expiredSegmentFileNames.size &&
            expiredSegments.all { it in snapshotSegments } &&
            if (cleanup.deleteWholeNamespace) {
                snapshotSegments == expiredSegments
            } else {
                expiredSegments.isNotEmpty()
            }
    }

    private fun scanLeaf(
        handles: SecureIdentityHandles,
        identityDirectories: List<String>,
        budget: NodeBudget,
    ): LeafInspection {
        if (!isTelemetryIdentityDirectories(identityDirectories)) return LeafInspection.Invalid
        val entries = mutableListOf<StoredTelemetryNamespaceEntry>()
        val orphanTemps = mutableListOf<Pair<Path, BasicFileAttributes>>()
        var segmentCount = 0
        var markerPresent = false
        val iterator = try {
            handles.leaf.iterator()
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return LeafInspection.Invalid
            throw failure
        }
        while (try {
                iterator.hasNext()
            } catch (failure: Exception) {
                if (failure.isTelemetryFilesystemBoundaryFailure()) return LeafInspection.Invalid
                throw failure
            }
        ) {
            val child = try {
                iterator.next()
            } catch (failure: Exception) {
                if (failure.isTelemetryFilesystemBoundaryFailure()) return LeafInspection.Invalid
                throw failure
            }
            if (!budget.visit()) return LeafInspection.Invalid
            val relative = child.fileName.takeIf(::isSingleRelativeComponent) ?: return LeafInspection.Invalid
            val name = relative.toString()
            val isMarker = name == CLIENT_TELEMETRY_MARKER_FILE
            val isSegment = CLIENT_TELEMETRY_SEGMENT_FILE_REGEX.matches(name)
            val isOrphanTemp = CLIENT_TELEMETRY_LEAF_ORPHAN_TEMP_FILE_REGEX.matches(name)
            if (!isMarker && !isSegment && !isOrphanTemp) return LeafInspection.Invalid
            val attributes = validatedSecureFile(handles, relative) ?: return LeafInspection.Invalid
            if (isOrphanTemp) {
                if (attributes.size() !in 0L..MAX_TELEMETRY_SEGMENT_BYTES || storageIdentity(attributes) == null) {
                    return LeafInspection.Invalid
                }
                orphanTemps.add(relative to attributes)
                continue
            }
            if (isMarker) {
                if (markerPresent || attributes.size() > MAX_MARKER_BYTES ||
                    readMarker(handles, relative, attributes) != CLIENT_TELEMETRY_MARKER_CONTENT
                ) {
                    return LeafInspection.Invalid
                }
                markerPresent = true
            } else {
                segmentCount++
                if (segmentCount > MAX_TELEMETRY_NAMESPACE_SEGMENTS ||
                    attributes.size() !in 1L..MAX_TELEMETRY_SEGMENT_BYTES ||
                    telemetrySegmentCreatedAtEpochMs(name) == null
                ) {
                    return LeafInspection.Invalid
                }
            }
            entries += attributes.toStoredEntry(name, storageIdentity(attributes))
        }
        when (forceDirectoryViaHandle(handles.leaf, handles.leafPath)) {
            ForceDirectoryResult.Success -> Unit
            ForceDirectoryResult.Unsupported -> return LeafInspection.Invalid
            ForceDirectoryResult.Retry -> return LeafInspection.Retry
        }
        orphanTemps.forEach { (relative, expected) ->
            val current = try {
                handles.leaf.getFileAttributeView(
                    relative,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )?.readAttributes()
            } catch (failure: Exception) {
                if (failure.isTelemetryFilesystemBoundaryFailure()) return LeafInspection.Invalid
                throw failure
            } ?: return LeafInspection.Invalid
            if (!current.isRegularFile || current.isSymbolicLink || current.isOther ||
                storageIdentity(current) != storageIdentity(expected) ||
                current.size() != expected.size() ||
                current.lastModifiedTime() != expected.lastModifiedTime()
            ) {
                return LeafInspection.Invalid
            }
            try {
                handles.leaf.deleteFile(relative)
            } catch (failure: Exception) {
                if (failure.isTelemetryFilesystemBoundaryFailure()) return LeafInspection.Invalid
                throw failure
            }
        }
        if (orphanTemps.isNotEmpty() &&
            forceDirectoryViaHandle(handles.leaf, handles.leafPath) != ForceDirectoryResult.Success
        ) {
            return LeafInspection.Retry
        }
        if (!markerPresent) {
            return if (entries.isEmpty()) LeafInspection.EmptyMarkerless else LeafInspection.Invalid
        }

        val retentionReference = entries.fold(handles.leafAttributes.lastModifiedTime().toMillis()) { newest, entry ->
            val reference = if (entry.fileName == CLIENT_TELEMETRY_MARKER_FILE) {
                entry.lastModifiedEpochMs
            } else {
                minOf(
                    checkNotNull(telemetrySegmentCreatedAtEpochMs(entry.fileName)),
                    entry.lastModifiedEpochMs,
                )
            }
            maxOf(newest, reference)
        }
        if (retentionReference < 0L) return LeafInspection.Invalid
        return LeafInspection.Valid(
            StoredTelemetryNamespace(
                identityDirectories = identityDirectories,
                retentionReferenceEpochMs = retentionReference,
                directoryStorageIdentity = storageIdentity(handles.leafAttributes),
                entries = entries.sortedBy(StoredTelemetryNamespaceEntry::fileName),
            ),
        )
    }

    private fun validatedSecureFile(
        handles: SecureIdentityHandles,
        relative: Path,
    ): BasicFileAttributes? {
        val absolute = handles.leafPath.resolve(relative).normalize()
        if (absolute.parent != handles.leafPath) return null
        val pathnameAttributes = validatedFile(absolute, handles.leafPath) ?: return null
        val secureAttributes = try {
            handles.leaf.getFileAttributeView(
                relative,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes()
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return null
            throw failure
        } ?: return null
        if (!secureAttributes.isRegularFile || secureAttributes.isSymbolicLink || secureAttributes.isOther ||
            !sameNioFileSnapshotIdentity(pathnameAttributes, secureAttributes)
        ) {
            return null
        }
        return secureAttributes
    }

    private fun readMarker(
        handles: SecureIdentityHandles,
        relative: Path,
        expected: BasicFileAttributes,
    ): String? {
        if (expected.size() !in 0L..MAX_MARKER_BYTES) return null
        val bytes = try {
            useResourcePreservingFatalFailure(handles.leaf.newByteChannel(relative, READ_OPTIONS)) read@{ channel ->
                if (channel.size() != expected.size()) return@read null
                val buffer = ByteBuffer.allocate(expected.size().toInt())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return@read null
                }
                buffer.array()
            } ?: return null
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return null
            throw failure
        }
        val after = try {
            handles.leaf.getFileAttributeView(
                relative,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes()
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return null
            throw failure
        } ?: return null
        if (!sameNioFileSnapshotIdentity(expected, after) ||
            expected.size() != after.size() ||
            expected.lastModifiedTime() != after.lastModifiedTime()
        ) {
            return null
        }
        return bytes.decodeToString()
    }

    private fun deleteSnapshotEntries(
        handles: SecureIdentityHandles,
        cleanup: TelemetryNamespaceCleanup,
    ): Boolean {
        val entriesByName = cleanup.snapshot.entries.associateBy(StoredTelemetryNamespaceEntry::fileName)
        val names = if (cleanup.deleteWholeNamespace) {
            cleanup.snapshot.entries.map(StoredTelemetryNamespaceEntry::fileName)
        } else {
            cleanup.expiredSegmentFileNames
        }
        for (name in names) {
            val relative = handles.leafPath.fileSystem.getPath(name)
            if (!isSingleRelativeComponent(relative)) return false
            val expected = entriesByName[name] ?: return false
            val current = try {
                handles.leaf.getFileAttributeView(
                    relative,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )?.readAttributes()
            } catch (failure: Exception) {
                if (failure.isTelemetryFilesystemBoundaryFailure()) return false
                throw failure
            } ?: return false
            if (!current.isRegularFile || current.isSymbolicLink || current.isOther ||
                expected.storageIdentity == null || storageIdentity(current) != expected.storageIdentity ||
                current.size() != expected.byteCount ||
                current.lastModifiedTime().toMillis() != expected.lastModifiedEpochMs
            ) {
                return false
            }
            try {
                handles.leaf.deleteFile(relative)
            } catch (failure: Exception) {
                if (failure.isTelemetryFilesystemBoundaryFailure()) return false
                throw failure
            }
        }
        if (names.isNotEmpty() &&
            forceDirectoryViaHandle(handles.leaf, handles.leafPath) != ForceDirectoryResult.Success
        ) {
            return false
        }
        if (!cleanup.deleteWholeNamespace) return true
        return handles.deleteLeafAndCompactEmptyParents()
    }

    private fun StoredTelemetryNamespace.hasStableDeletionIdentities(): Boolean =
        directoryStorageIdentity != null && entries.all { it.storageIdentity != null }

    private fun resolveIdentity(identityDirectories: List<String>): Path =
        identityDirectories.fold(telemetryRoot) { parent, component -> parent.resolve(component) }.normalize()

    private fun validatedFile(path: Path, parent: Path): BasicFileAttributes? = try {
        requirePrivateFile(path, parent)
    } catch (failure: Exception) {
        if (failure.isTelemetryFilesystemBoundaryFailure()) null else throw failure
    }

    private fun forceDirectoryViaHandle(
        stream: SecureDirectoryStream<Path>,
        path: Path,
    ): ForceDirectoryResult = try {
        val before = stream.getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes()
            ?: return ForceDirectoryResult.Unsupported
        val expectedStorageIdentity = storageIdentity(before) ?: return ForceDirectoryResult.Unsupported
        forceDirectory(path, expectedStorageIdentity)
        val after = stream.getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes()
            ?: return ForceDirectoryResult.Retry
        if (storageIdentity(after) == expectedStorageIdentity) {
            ForceDirectoryResult.Success
        } else {
            ForceDirectoryResult.Retry
        }
    } catch (_: UnsupportedOperationException) {
        ForceDirectoryResult.Unsupported
    } catch (failure: Exception) {
        if (failure.isTelemetryFilesystemBoundaryFailure()) ForceDirectoryResult.Retry else throw failure
    }

    private fun <T> withSecureTelemetryRoot(
        action: (SecureTelemetryRoot) -> T,
    ): SecureRootAccess<T> {
        val raw = try {
            openRoot(dataRoot)
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return SecureRootAccess.Invalid
            throw failure
        }
        return try {
            useResourcePreservingFatalFailure(raw) { directoryStream ->
                if (directoryStream !is SecureDirectoryStream<*>) {
                    return@useResourcePreservingFatalFailure SecureRootAccess.Unsupported
                }
                @Suppress("UNCHECKED_CAST")
                val root = directoryStream as SecureDirectoryStream<Path>
                val expected = requirePrivateDirectory(telemetryRoot, dataRoot)
                val relative = dataRoot.fileSystem.getPath(CLIENT_TELEMETRY_ROOT_DIRECTORY)
                val telemetry = root.newDirectoryStream(relative, LinkOption.NOFOLLOW_LINKS)
                useResourcePreservingFatalFailure(telemetry) {
                    val actual = telemetry.getFileAttributeView(BasicFileAttributeView::class.java)
                        ?.readAttributes()
                        ?: return@useResourcePreservingFatalFailure SecureRootAccess.Invalid
                    if (!actual.isDirectory || actual.isSymbolicLink || actual.isOther ||
                        !sameNioFileSnapshotIdentity(expected, actual)
                    ) {
                        return@useResourcePreservingFatalFailure SecureRootAccess.Invalid
                    }
                    SecureRootAccess.Ready(
                        action(SecureTelemetryRoot(telemetry)),
                    )
                }
            }
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) SecureRootAccess.Invalid else throw failure
        }
    }

    private fun <T> withIdentity(
        root: SecureTelemetryRoot,
        identityDirectories: List<String>,
        action: (SecureIdentityHandles) -> T,
    ): IdentityAccess<T> {
        if (!isTelemetryIdentityDirectories(identityDirectories)) return IdentityAccess.Invalid
        val opened = try {
            openIdentity(root, identityDirectories)
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return IdentityAccess.Invalid
            throw failure
        }
        if (opened !is IdentityOpen.Ready) {
            return when (opened) {
                IdentityOpen.Invalid -> IdentityAccess.Invalid
                IdentityOpen.Missing -> IdentityAccess.Missing
                IdentityOpen.Retry -> IdentityAccess.Retry
                is IdentityOpen.Ready -> error("unreachable")
            }
        }
        return try {
            IdentityAccess.Ready(useResourcePreservingFatalFailure(opened.handles, action))
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) IdentityAccess.Invalid else throw failure
        }
    }

    private fun openIdentity(
        root: SecureTelemetryRoot,
        identityDirectories: List<String>,
    ): IdentityOpen {
        val paths = mutableListOf<Path>()
        var parentPath = telemetryRoot
        identityDirectories.forEach { component ->
            parentPath = parentPath.resolve(component).normalize()
            paths.add(parentPath)
        }
        val deploymentResult = openValidatedChild(root.stream, telemetryRoot, paths[0])
        val deployment = when (deploymentResult) {
            is DirectoryOpen.Ready -> deploymentResult.stream
            DirectoryOpen.Invalid -> return IdentityOpen.Invalid
            DirectoryOpen.Missing -> return durableMissing(
                root.stream,
                telemetryRoot,
                paths[0],
            )
        }
        val datasetResult = openValidatedChild(deployment, paths[0], paths[1])
        val dataset = when (datasetResult) {
            is DirectoryOpen.Ready -> datasetResult.stream
            DirectoryOpen.Invalid -> {
                closeSecureStreams(deployment)
                return IdentityOpen.Invalid
            }
            DirectoryOpen.Missing -> {
                val missing = durableMissing(
                    deployment,
                    paths[0],
                    paths[1],
                )
                closeSecureStreams(deployment)
                return missing
            }
        }
        val leafResult = openValidatedChild(dataset, paths[1], paths[2])
        if (leafResult !is DirectoryOpen.Ready) {
            val missing = if (leafResult == DirectoryOpen.Missing) {
                durableMissing(dataset, paths[1], paths[2])
            } else {
                IdentityOpen.Invalid
            }
            closeSecureStreams(dataset, deployment)
            return missing
        }
        return IdentityOpen.Ready(
            SecureIdentityHandles(
                telemetryRoot = root.stream,
                deployment = deployment,
                dataset = dataset,
                leafStream = leafResult.stream,
                deploymentPath = paths[0],
                datasetPath = paths[1],
                leafPath = paths[2],
                leafAttributes = leafResult.attributes,
                telemetryRootPath = telemetryRoot,
                storageIdentity = storageIdentity,
                forceDirectory = ::forceDirectoryViaHandle,
            ),
        )
    }

    private fun durableMissing(
        parent: SecureDirectoryStream<Path>,
        parentPath: Path,
        childPath: Path,
    ): IdentityOpen {
        when (forceDirectoryViaHandle(parent, parentPath)) {
            ForceDirectoryResult.Success -> Unit
            ForceDirectoryResult.Unsupported -> return IdentityOpen.Invalid
            ForceDirectoryResult.Retry -> return IdentityOpen.Retry
        }
        if (childPath.parent != parentPath) return IdentityOpen.Invalid
        val relative = childPath.fileName.takeIf(::isSingleRelativeComponent) ?: return IdentityOpen.Invalid
        val view = parent.getFileAttributeView(
            relative,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: return IdentityOpen.Retry
        return try {
            view.readAttributes()
            IdentityOpen.Retry
        } catch (_: NoSuchFileException) {
            IdentityOpen.Missing
        } catch (_: NotDirectoryException) {
            IdentityOpen.Retry
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return IdentityOpen.Retry
            throw failure
        }
    }

    private fun openValidatedChild(
        parent: SecureDirectoryStream<Path>,
        parentPath: Path,
        childPath: Path,
    ): DirectoryOpen {
        if (childPath.parent != parentPath) return DirectoryOpen.Invalid
        val relative = childPath.fileName.takeIf(::isSingleRelativeComponent) ?: return DirectoryOpen.Invalid
        val expected = try {
            requirePrivateDirectory(childPath, parentPath)
        } catch (_: NoSuchFileException) {
            return DirectoryOpen.Missing
        } catch (_: NotDirectoryException) {
            return DirectoryOpen.Invalid
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return DirectoryOpen.Invalid
            throw failure
        }
        val stream = try {
            parent.newDirectoryStream(relative, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return DirectoryOpen.Missing
        } catch (_: NotDirectoryException) {
            return DirectoryOpen.Invalid
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return DirectoryOpen.Invalid
            throw failure
        }
        return try {
            val actual = stream.getFileAttributeView(BasicFileAttributeView::class.java)
                ?.readAttributes()
                ?: return DirectoryOpen.Invalid.also {
                    closeAllResourcesPreservingFatalFailure(stream::close)?.let { failure -> throw failure }
                }
            if (!actual.isDirectory || actual.isSymbolicLink || actual.isOther ||
                !sameNioFileSnapshotIdentity(expected, actual)
            ) {
                closeAllResourcesPreservingFatalFailure(stream::close)?.let { failure -> throw failure }
                DirectoryOpen.Invalid
            } else {
                DirectoryOpen.Ready(stream, actual)
            }
        } catch (failure: Exception) {
            val closeFailure = closeAllResourcesPreservingFatalFailure(stream::close)
            val terminal = closeFailure?.let { mergeSessionLifecycleFailures(failure, it) } ?: failure
            if (terminal !== failure || !failure.isTelemetryFilesystemBoundaryFailure()) throw terminal
            DirectoryOpen.Invalid
        }
    }

    private companion object {
        const val MAX_MARKER_BYTES = 32L
        const val IDENTITY_DIRECTORY_DEPTH = 3
        const val MAX_NODES_PER_IDENTITY =
            IDENTITY_DIRECTORY_DEPTH + MAX_TELEMETRY_NAMESPACE_SEGMENTS + 1 + 1
        val READ_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }
}
