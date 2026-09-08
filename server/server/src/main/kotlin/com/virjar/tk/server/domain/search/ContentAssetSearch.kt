package com.virjar.tk.server.domain.search

import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.server.domain.document.DocumentReadAccessSnapshot
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext

data class ContentAssetKey(val kind: Int, val id: String)
data class ContentAssetProjection(
    val key: ContentAssetKey,
    val scopeId: String,
    val revision: Long,
    val updatedAt: Long,
    val active: Boolean,
    val title: String,
    val text: String,
    val mimeType: String? = null,
)
data class ContentAssetPending(val key: ContentAssetKey, val revision: Long)
data class ContentAssetAnchor(val updatedAt: Long, val id: String)
data class ContentAssetMatch(val key: ContentAssetKey, val scopeId: String, val revision: Long, val updatedAt: Long)

/** PostgreSQL owns current objects and a coalesced, same-transaction projection queue. */
interface ContentAssetRepository {
    fun pending(limit: Int): List<ContentAssetPending>
    fun acknowledge(pending: ContentAssetPending)
    fun projection(key: ContentAssetKey): ContentAssetProjection?
    fun scan(kind: Int, afterId: String?, limit: Int): List<ContentAssetProjection>
    fun documentScopes(transaction: PgReadTransactionContext, uid: String, scopeId: String): Set<String>
    fun documentAccess(transaction: PgReadTransactionContext, uid: String, spaceIds: Set<String>): DocumentReadAccessSnapshot
    /** Joins the caller's current PostgreSQL read snapshot. Never returns content bodies or paths. */
    fun currentHits(kind: Int, ids: Set<String>): Map<String, ContentSearchHit>
}

/** No Lucene state or PostgreSQL implementation escapes these ports. */
interface ContentAssetIndex {
    fun search(request: ContentSearchRequest, scopes: Set<String>, after: ContentAssetAnchor?, limit: Int): List<ContentAssetMatch>
}

fun interface ContentAssetProjectionRecovery { fun catchUp() }

class ContentSearchUnavailableException : RuntimeException("内容搜索索引暂不可用，请稍后重试")
