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

/** PostgreSQL 持有当前对象以及合并进同一事务的投影队列。 */
interface ContentAssetRepository {
    fun pending(limit: Int): List<ContentAssetPending>
    fun acknowledge(pending: ContentAssetPending)
    fun projection(key: ContentAssetKey): ContentAssetProjection?
    fun scan(kind: Int, afterId: String?, limit: Int): List<ContentAssetProjection>
    fun documentScopes(transaction: PgReadTransactionContext, uid: String, scopeId: String): Set<String>
    fun documentAccess(transaction: PgReadTransactionContext, uid: String, spaceIds: Set<String>): DocumentReadAccessSnapshot
    /** 加入调用方当前的 PostgreSQL 读快照。绝不返回内容正文或路径。 */
    fun currentHits(kind: Int, ids: Set<String>): Map<String, ContentSearchHit>
}

/** 任何 Lucene 状态或 PostgreSQL 实现都不会逃出这些端口。 */
interface ContentAssetIndex {
    fun search(request: ContentSearchRequest, scopes: Set<String>, after: ContentAssetAnchor?, limit: Int): List<ContentAssetMatch>
}

fun interface ContentAssetProjectionRecovery { fun catchUp() }

class ContentSearchUnavailableException : RuntimeException("内容搜索索引暂不可用，请稍后重试")
