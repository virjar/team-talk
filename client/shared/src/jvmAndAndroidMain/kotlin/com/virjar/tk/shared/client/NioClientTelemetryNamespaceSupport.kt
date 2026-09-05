package com.virjar.tk.shared.client

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** 遥测命名空间维护操作共享的安全句柄与有界结果类型。 */
internal class SecureIdentityHandles(
    private val telemetryRoot: SecureDirectoryStream<Path>,
    private val deployment: SecureDirectoryStream<Path>,
    private val dataset: SecureDirectoryStream<Path>,
    leafStream: SecureDirectoryStream<Path>,
    private val deploymentPath: Path,
    private val datasetPath: Path,
    val leafPath: Path,
    val leafAttributes: BasicFileAttributes,
    private val telemetryRootPath: Path,
    private val storageIdentity: (BasicFileAttributes) -> Any?,
    private val forceDirectory: (SecureDirectoryStream<Path>, Path) -> ForceDirectoryResult,
) : AutoCloseable {
    var leaf: SecureDirectoryStream<Path> = leafStream
        private set
    private var leafOpen = true
    private var datasetOpen = true
    private var deploymentOpen = true

    fun deleteLeafAndCompactEmptyParents(): Boolean {
        val expectedLeafIdentity = storageIdentity(leafAttributes) ?: return false
        val uid = leafPath.fileName.takeIf(::isSingleRelativeComponent) ?: return false
        val currentLeaf = try {
            dataset.getFileAttributeView(
                uid,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes()
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return false
            throw failure
        } ?: return false
        if (!currentLeaf.isDirectory || currentLeaf.isSymbolicLink || currentLeaf.isOther ||
            storageIdentity(currentLeaf) != expectedLeafIdentity
        ) {
            return false
        }
        closeLeaf()
        try {
            dataset.deleteDirectory(uid)
        } catch (_: DirectoryNotEmptyException) {
            return false
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return false
            throw failure
        }
        if (forceDirectory(dataset, datasetPath) != ForceDirectoryResult.Success) return false

        if (!isEmpty(dataset)) return true
        closeDataset()
        val datasetName = datasetPath.fileName.takeIf(::isSingleRelativeComponent) ?: return true
        try {
            deployment.deleteDirectory(datasetName)
        } catch (_: DirectoryNotEmptyException) {
            return true
        } catch (failure: Exception) {
            if (failure.isTelemetryFilesystemBoundaryFailure()) return true
            throw failure
        }
        if (forceDirectory(deployment, deploymentPath) != ForceDirectoryResult.Success) return false

        if (!isEmpty(deployment)) return true
        closeDeployment()
        val deploymentName = deploymentPath.fileName.takeIf(::isSingleRelativeComponent) ?: return true
        try {
            telemetryRoot.deleteDirectory(deploymentName)
        } catch (_: DirectoryNotEmptyException) {
            // 空检查之后出现了兄弟项；目标叶子已经被退役。
        } catch (failure: Exception) {
            if (!failure.isTelemetryFilesystemBoundaryFailure()) throw failure
        }
        if (forceDirectory(telemetryRoot, telemetryRootPath) != ForceDirectoryResult.Success) return false
        return true
    }

    override fun close() {
        closeAllResourcesPreservingFatalFailure(::closeLeaf, ::closeDataset, ::closeDeployment)
            ?.let { throw it }
    }

    private fun closeLeaf() {
        if (!leafOpen) return
        leafOpen = false
        leaf.close()
    }

    private fun closeDataset() {
        if (!datasetOpen) return
        datasetOpen = false
        dataset.close()
    }

    private fun closeDeployment() {
        if (!deploymentOpen) return
        deploymentOpen = false
        deployment.close()
    }

    private fun isEmpty(stream: SecureDirectoryStream<Path>): Boolean = try {
        !stream.iterator().hasNext()
    } catch (failure: Exception) {
        if (failure.isTelemetryFilesystemBoundaryFailure()) false else throw failure
    }
}

internal class NodeBudget(private val maximum: Int) {
    var visited: Int = 0
        private set

    fun visit(): Boolean {
        if (visited >= maximum) return false
        visited++
        return true
    }
}

internal sealed class SecureRootAccess<out T> {
    data class Ready<T>(val value: T) : SecureRootAccess<T>()
    data object Unsupported : SecureRootAccess<Nothing>()
    data object Invalid : SecureRootAccess<Nothing>()
}

internal enum class ForceDirectoryResult {
    Success,
    Unsupported,
    Retry,
}

internal data class SecureTelemetryRoot(
    val stream: SecureDirectoryStream<Path>,
)

internal data class RegistryPageInspection(
    val registry: ClientTelemetryNamespaceRegistry,
    val namespaces: List<StoredTelemetryNamespace>,
    val visitedNodes: Int,
    val truncated: Boolean,
    val needsImmediateRetry: Boolean,
)

internal sealed class LeafInspection {
    data class Valid(val namespace: StoredTelemetryNamespace) : LeafInspection()
    data object EmptyMarkerless : LeafInspection()
    data object Invalid : LeafInspection()
    data object Retry : LeafInspection()
}

internal sealed class IdentityAccess<out T> {
    data class Ready<T>(val value: T) : IdentityAccess<T>()
    data object Missing : IdentityAccess<Nothing>()
    data object Invalid : IdentityAccess<Nothing>()
    data object Retry : IdentityAccess<Nothing>()
}

internal sealed class IdentityOpen {
    data class Ready(val handles: SecureIdentityHandles) : IdentityOpen()
    data object Missing : IdentityOpen()
    data object Invalid : IdentityOpen()
    data object Retry : IdentityOpen()
}

internal sealed class DirectoryOpen {
    data class Ready(
        val stream: SecureDirectoryStream<Path>,
        val attributes: BasicFileAttributes,
    ) : DirectoryOpen()

    data object Missing : DirectoryOpen()
    data object Invalid : DirectoryOpen()
}

internal fun <T> SecureRootAccess<IdentityAccess<T>>.readyIdentityValue(): T? =
    ((this as? SecureRootAccess.Ready)?.value as? IdentityAccess.Ready)?.value

internal fun isSingleRelativeComponent(path: Path): Boolean =
    !path.isAbsolute && path.nameCount == 1 && path.toString().let { it != "." && it != ".." }

internal fun Exception.isTelemetryFilesystemBoundaryFailure(): Boolean =
    this is IOException ||
        this is DirectoryIteratorException && cause is IOException ||
        this is SecurityException ||
        this is IllegalArgumentException ||
        this is UnsupportedOperationException

internal fun closeSecureStreams(vararg streams: SecureDirectoryStream<Path>) {
    val closes = Array<() -> Unit>(streams.size) { index ->
        { streams[index].close() }
    }
    closeAllResourcesPreservingFatalFailure(*closes)
        ?.let { throw it }
}

internal fun sameTelemetryLockFile(
    before: BasicFileAttributes,
    after: BasicFileAttributes,
): Boolean = sameNioFileSnapshotIdentity(before, after)

internal fun BasicFileAttributes.toStoredEntry(
    fileName: String,
    storageIdentity: Any?,
): StoredTelemetryNamespaceEntry =
    StoredTelemetryNamespaceEntry(
        fileName = fileName,
        byteCount = size(),
        lastModifiedEpochMs = lastModifiedTime().toMillis(),
        storageIdentity = storageIdentity,
    )
