package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.DocumentHomeItem

/** 仓库拥有的最近文档投影，带一条普通 latest-request-wins 通道。 */
internal class LocalDocumentHomeProjectionStore(
    private val queries: AppDatabaseQueries,
    private val persistence: LocalDocumentProjectionPersistence,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    val gate = KeyedProjectionSnapshotGate("document home snapshot")

    fun get(collection: DocumentHomeCollection): List<DocumentHomeItem> = cacheUseGate.use {
        synchronized(stateLock) {
            queries.selectDocumentHome(collection.storageCode).executeAsList().map { row ->
                DocumentHomeItem(
                    documentId = row.document_id,
                    spaceId = row.space_id,
                    spaceName = row.space_name,
                    title = row.title,
                    excerpt = row.excerpt,
                    createdBy = row.created_by,
                    creatorName = row.creator_name,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                    accessedAt = row.accessed_at,
                )
            }
        }
    }

    fun isCached(collection: DocumentHomeCollection): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            queries.isDocumentProjectionCached(projectionCode(collection)).executeAsOne() > 0L
        }
    }

    fun begin(collection: DocumentHomeCollection): ProjectionSnapshotLease = cacheUseGate.use {
        synchronized(stateLock) { gate.begin(snapshotKey(collection)) }
    }

    fun apply(
        lease: ProjectionSnapshotLease,
        collection: DocumentHomeCollection,
        items: List<DocumentHomeItem>,
    ): Boolean = cacheUseGate.runIfOpen {
        val snapshot = LocalDocumentProjectionPolicy.normalizeHome(items)
        synchronized(stateLock) {
            if (!gate.consumeIfCurrent(lease, snapshotKey(collection))) return@synchronized false
            queries.transaction {
                queries.deleteDocumentHome(collection.storageCode)
                snapshot.forEachIndexed { index, item ->
                    persistence.persistHomeLocked(collection, index, item)
                }
                queries.markDocumentProjectionCached(projectionCode(collection))
            }
            true
        }
    }

    fun reset() = gate.reset()

    fun abandon(lease: ProjectionSnapshotLease): Boolean = gate.abandon(lease)

    private fun snapshotKey(collection: DocumentHomeCollection): String =
        "home:${collection.storageCode}"

    private fun projectionCode(collection: DocumentHomeCollection): Long = when (collection) {
        DocumentHomeCollection.RECENT -> RECENT_HOME_PROJECTION_CODE
        DocumentHomeCollection.RECENTLY_CREATED -> CREATED_HOME_PROJECTION_CODE
    }

    private companion object {
        const val RECENT_HOME_PROJECTION_CODE = 2L
        const val CREATED_HOME_PROJECTION_CODE = 3L
    }
}
