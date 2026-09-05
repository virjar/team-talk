package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.model.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val MAX_CONCURRENT_DOCUMENT_PATH_REFRESHES = 4

/** 一个文档的每一个打开实例共享的稳定服务器身份。 */
internal data class DocumentPathRefreshKey(
    val spaceId: String,
    val documentId: String,
)

/** 在拥有的刷新批次启动时仍未解析的一个 tab 实例。 */
internal data class DocumentPathRefreshTarget(
    val instanceId: Long,
    val key: DocumentPathRefreshKey,
)

internal sealed interface DocumentPathRefreshBatch {
    /** 更新的 session 批次拥有发布权；这个批次绝不能报告或合并任何结果。 */
    data object Superseded : DocumentPathRefreshBatch

    data class Current(
        val targets: List<DocumentPathRefreshTarget>,
        val documents: Map<DocumentPathRefreshKey, Document>,
        val failures: List<Throwable>,
    ) : DocumentPathRefreshBatch
}

/** 可以在批次发布已解析 tab 之前准备的完整路径结果。 */
internal data class AcceptedDocumentPathRefresh(
    val document: Document,
    val previousParentIds: Set<String?>,
)

/**
 * move 之后路径重校验的 session 级 owner。
 *
 * 同一时间只有一个批次可以发出 RPC。更新的请求立即让更旧的 owner 失效；
 * 更旧的批次至多完成它当前的有界波次，并且绝不能发布。文档与 tab 实例独立地去重，
 * 而每一个波次创建的子协程不超过 [maxConcurrency] 个。可重试的 session 级失败
 * 在该波次之后停止批次，这样离线客户端就不会串行探测每一个打开的文档。
 */
internal class DocumentPathRefreshCoordinator(
    private val maxConcurrency: Int = MAX_CONCURRENT_DOCUMENT_PATH_REFRESHES,
) {
    private val ownershipMutex = Mutex()
    private val batchMutex = Mutex()
    private var ownerGeneration = 0L

    init {
        require(maxConcurrency > 0) { "Document path refresh concurrency must be positive" }
    }

    suspend fun refresh(
        spaceId: String,
        currentTargets: () -> List<DocumentPathRefreshTarget>,
        fetch: suspend (DocumentPathRefreshKey) -> Document,
        publish: (DocumentPathRefreshBatch.Current) -> Unit = {},
    ): DocumentPathRefreshBatch {
        val owner = claimOwner()
        return batchMutex.withLock {
            if (!isCurrent(owner)) return@withLock DocumentPathRefreshBatch.Superseded

            val targets = currentTargets().filter { it.key.spaceId == spaceId }
            val keys = targets.asSequence().map(DocumentPathRefreshTarget::key).distinct().toList()
            if (keys.isEmpty()) {
                val current = DocumentPathRefreshBatch.Current(targets, emptyMap(), emptyList())
                return@withLock publishIfCurrent(owner, current, publish)
            }

            val documents = linkedMapOf<DocumentPathRefreshKey, Document>()
            val failures = mutableListOf<Throwable>()
            var offset = 0
            while (offset < keys.size && isCurrent(owner)) {
                val end = minOf(offset + maxConcurrency, keys.size)
                val wave = coroutineScope {
                    keys.subList(offset, end).map { key ->
                        async {
                            try {
                                DocumentPathRefreshResult.Loaded(key, fetch(key))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                DocumentPathRefreshResult.Failed(failure)
                            }
                        }
                    }.awaitAll()
                }
                if (!isCurrent(owner)) {
                    return@withLock DocumentPathRefreshBatch.Superseded
                }

                var stopAfterWave = false
                wave.forEach { result ->
                    when (result) {
                        is DocumentPathRefreshResult.Loaded -> documents[result.key] = result.document
                        is DocumentPathRefreshResult.Failed -> {
                            failures += result.failure
                            if (result.failure.stopsDocumentPathRefreshBatch()) stopAfterWave = true
                        }
                    }
                }
                if (stopAfterWave) break
                offset = end
            }
            val current = DocumentPathRefreshBatch.Current(targets, documents, failures)
            publishIfCurrent(owner, current, publish)
        }
    }

    private suspend fun claimOwner(): Long = ownershipMutex.withLock {
        check(ownerGeneration < Long.MAX_VALUE) { "Document path refresh generation exhausted" }
        ++ownerGeneration
    }

    private suspend fun isCurrent(owner: Long): Boolean = ownershipMutex.withLock {
        ownerGeneration == owner
    }

    /** 所有权检查和不可挂起的 Compose 发布是一个线性化步骤。 */
    private suspend fun publishIfCurrent(
        owner: Long,
        current: DocumentPathRefreshBatch.Current,
        publish: (DocumentPathRefreshBatch.Current) -> Unit,
    ): DocumentPathRefreshBatch = ownershipMutex.withLock {
        if (ownerGeneration != owner) return@withLock DocumentPathRefreshBatch.Superseded
        publish(current)
        current
    }
}

/**
 * move 可能在其原始 tab 关闭之后完成，因此响应不能合并进它捕获的实例。
 * 被移动页面的整个同空间子树现在可能有不同的祖先链；
 * 撤销每一个打开的服务器支撑路径，并让这个协调器重新校验每一个。
 */
internal fun invalidateOpenDocumentPathsAfterUnmergedMove(
    tabs: List<DocumentTabState>,
    request: DocumentMoveRequest,
): List<DocumentTabState> {
    var changed = false
    val invalidated = tabs.map { tab ->
        if (tab.spaceId == request.spaceId && tab.documentId != null &&
            !tab.creating && tab.pathResolved
        ) {
            changed = true
            tab.copy(pathResolved = false)
        } else {
            tab
        }
    }
    return if (changed) invalidated else tabs
}

/** 快照未解析实例，而不在 RPC 挂起期间保留可变的 tab 对象。 */
internal fun unresolvedDocumentPathRefreshTargets(
    tabs: List<DocumentTabState>,
    spaceId: String,
): List<DocumentPathRefreshTarget> = tabs.mapNotNull { tab ->
    val documentId = tab.documentId
    if (tab.spaceId != spaceId || tab.pathResolved || documentId == null) return@mapNotNull null
    DocumentPathRefreshTarget(
        instanceId = tab.instanceId,
        key = DocumentPathRefreshKey(spaceId, documentId),
    )
}

/**
 * 只合并进当前批次捕获的那个确切的、仍然打开的未解析实例。
 *
 * 导航可能已经解析了一个 tab，或者可能用另一个实例 ID 关闭并重新打开了同一个文档。
 * 这两种情况都保留它们更新的状态，并拒绝这个批次的迟到值。
 */
internal fun mergeDocumentPathRefreshBatch(
    tabs: List<DocumentTabState>,
    batch: DocumentPathRefreshBatch.Current,
): List<DocumentTabState> {
    var mutable: MutableList<DocumentTabState>? = null
    batch.targets.forEach { target ->
        val document = batch.documents[target.key] ?: return@forEach
        val currentTabs = mutable ?: tabs
        val index = currentTabs.indexOfFirst { tab ->
            tab.instanceId == target.instanceId && tab.documentId == target.key.documentId &&
                tab.spaceId == target.key.spaceId && !tab.pathResolved
        }
        if (index < 0) return@forEach
        val refreshed = refreshRestoredDocumentPath(currentTabs[index], document) ?: return@forEach
        if (refreshed != currentTabs[index]) {
            val next = mutable ?: tabs.toMutableList().also { mutable = it }
            next[index] = refreshed
        }
    }
    return mutable ?: tabs
}

/**
 * 只解析仍被未解析的捕获实例接受的完整文档。树的驱逐可以在
 * [mergeDocumentPathRefreshBatch] 把对应路径发布为已解析之前运行。
 */
internal fun acceptedDocumentPathRefreshes(
    tabs: List<DocumentTabState>,
    batch: DocumentPathRefreshBatch.Current,
): List<AcceptedDocumentPathRefresh> {
    val tabsByInstanceId = tabs.associateBy(DocumentTabState::instanceId)
    return batch.documents.mapNotNull { (key, document) ->
        if (document.documentId != key.documentId || document.spaceId != key.spaceId) {
            return@mapNotNull null
        }
        val previousParentIds = linkedSetOf<String?>()
        batch.targets.asSequence()
            .filter { it.key == key }
            .forEach { target ->
                val current = tabsByInstanceId[target.instanceId] ?: return@forEach
                if (current.documentId != key.documentId || current.spaceId != key.spaceId ||
                    current.pathResolved || refreshRestoredDocumentPath(current, document) == null
                ) return@forEach
                previousParentIds += current.parentId
            }
        previousParentIds.takeIf { it.isNotEmpty() }?.let { parents ->
            AcceptedDocumentPathRefresh(document, parents)
        }
    }
}

private sealed interface DocumentPathRefreshResult {
    data class Loaded(
        val key: DocumentPathRefreshKey,
        val document: Document,
    ) : DocumentPathRefreshResult

    data class Failed(
        val failure: Throwable,
    ) : DocumentPathRefreshResult
}

private fun Throwable.stopsDocumentPathRefreshBatch(): Boolean =
    this === AppError.Network || this === AppError.Timeout || this === AppError.AuthExpired ||
        (this is AppError.Business && code == 403)
