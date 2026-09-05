package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.DocumentHomeCollection
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.DocumentRepository
import com.virjar.tk.shared.repository.DocumentSpaceRefreshCycle
import com.virjar.tk.shared.repository.DocumentSpaceRefreshPageResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一份持久化的空快照也是数据；[known] 把它与缓存 miss 区分开。 */
internal data class CachedDocumentSnapshot<out T>(
    val known: Boolean,
    val value: T,
)

internal data class CachedDocumentHome(
    val recent: CachedDocumentSnapshot<List<DocumentHomeItem>>,
    val recentlyCreated: CachedDocumentSnapshot<List<DocumentHomeItem>>,
) {
    val hasAnySnapshot: Boolean get() = recent.known || recentlyCreated.known
}

internal fun shouldRemoveDocumentSpaceProjection(
    failure: AppError.Business,
    spaceId: String?,
    notFoundRetiresSpace: Boolean,
): Boolean = spaceId != null &&
    (failure.code == 403 || failure.code == 404 && notFoundRetiresSpace)

/** 围绕写缓存文档 repository 的应用层发布边界。 */
internal class DocumentRepositoryBoundary(
    private val session: ClientSession,
    private val localData: UiLocalDataBoundary,
    private val onSpaceProjectionRemoved: (String, AppError.Business) -> Unit,
) {
    private val featureRequestOwner = Mutex()

    suspend fun <T> call(
        spaceId: String? = null,
        notFoundRetiresSpace: Boolean = false,
        block: suspend DocumentRepository.() -> T,
    ): T = featureRequestOwner.withLock {
        try {
            localData.run { session.documentRepo.block() }
        } catch (failure: AppError.Business) {
            if (shouldRemoveDocumentSpaceProjection(failure, spaceId, notFoundRetiresSpace)) {
                onSpaceProjectionRemoved(requireNotNull(spaceId), failure)
            }
            throw failure
        }
    }
}

/** 本地/远程读取边界。每一次 repository 调用都保持在 UI dispatcher 之外。 */
internal class DocumentWorkspaceReadGateway(
    private val repository: DocumentRepositoryBoundary,
) {
    suspend fun cachedSpaces(): CachedDocumentSnapshot<List<DocumentSpace>> =
        repository.call {
            CachedDocumentSnapshot(
                known = isSpaceSnapshotCached(),
                value = cachedSpaces(),
            )
        }

    suspend fun beginSpaceRefreshCycle(): DocumentSpaceRefreshCycle = repository.call {
        beginSpaceRefreshCycle()
    }

    suspend fun refreshSpaces(
        cycle: DocumentSpaceRefreshCycle,
        cursor: String? = null,
    ): DocumentSpaceRefreshPageResult = repository.call {
        refreshSpacesPage(cycle, cursor).getOrThrow()
    }

    suspend fun cachedDocument(spaceId: String, documentId: String): Document? =
        repository.call { cachedDocument(spaceId, documentId) }

    suspend fun refreshDocument(spaceId: String, documentId: String): Document =
        repository.call(spaceId = spaceId) { getDocument(spaceId, documentId).getOrThrow() }

    suspend fun cachedNodePathSpine(
        spaceId: String,
        nodeId: String,
    ): DocumentPathSpine? = repository.call { cachedNodePathSpine(spaceId, nodeId) }

    suspend fun refreshNodePathSpine(
        spaceId: String,
        nodeId: String,
    ): DocumentPathSpine = repository.call {
        getNodePathSpine(spaceId, nodeId).getOrThrow().also { spine ->
            check(spine.spaceId == spaceId && spine.targetNodeId == nodeId) {
                "document path spine response escaped its requested identity"
            }
        }
    }

    suspend fun cachedNodes(
        spaceId: String,
        parentId: String?,
    ): CachedDocumentSnapshot<List<DocumentNode>> = repository.call {
        CachedDocumentSnapshot(
            known = isBranchCached(spaceId, parentId),
            value = cachedNodes(spaceId, parentId),
        )
    }

    suspend fun refreshNodes(spaceId: String, parentId: String?): List<DocumentNode> =
        repository.call(
            spaceId = spaceId,
            notFoundRetiresSpace = parentId == null,
        ) { listNodes(spaceId, parentId).getOrThrow() }

    suspend fun cachedHome(): CachedDocumentHome = repository.call {
        CachedDocumentHome(
            recent = CachedDocumentSnapshot(
                known = isHomeSnapshotCached(DocumentHomeCollection.RECENT),
                value = cachedHome(DocumentHomeCollection.RECENT),
            ),
            recentlyCreated = CachedDocumentSnapshot(
                known = isHomeSnapshotCached(
                    DocumentHomeCollection.RECENTLY_CREATED,
                ),
                value = cachedHome(DocumentHomeCollection.RECENTLY_CREATED),
            ),
        )
    }

    suspend fun refreshHome(): Pair<List<DocumentHomeItem>, List<DocumentHomeItem>> =
        loadDocumentWorkspaceHome(
            repository = repository,
        )
}
