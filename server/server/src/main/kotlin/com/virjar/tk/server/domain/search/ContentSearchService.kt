package com.virjar.tk.server.domain.search

import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.document.DocumentAuthorizationPolicy
import com.virjar.tk.server.domain.document.DocumentCapability
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork

/** Bounded aggregation entry point; each selected domain owns authorization and its search projection. */
class ContentSearchService(
    private val source: ContentAssetRepository,
    private val index: ContentAssetIndex,
    private val recovery: ContentAssetProjectionRecovery,
    private val unitOfWork: PgUnitOfWork,
    private val chats: ChatAccess,
    private val messages: MessageService,
) {
    suspend fun search(uid: String, request: ContentSearchRequest): ContentSearchPage {
        if (request.kind == ContentSearchRequest.KIND_CHAT_ATTACHMENT) return messages.searchAttachments(uid, request)
        val fingerprint = reliableCommandFingerprint("asset-search", uid, request.kind.toString(), request.keyword, request.scopeId, request.fileType.toString())
        val after = decodeCursor(request.cursor, fingerprint)
        // Drain before opening the read snapshot: no authoritative write waits for Lucene under its lock.
        recovery.catchUp()
        return if (request.kind == ContentSearchRequest.KIND_DOCUMENT) {
            unitOfWork.read {
                val scopes = source.documentScopes(transaction, uid, request.scopeId)
                searchCurrent(uid, request, scopes, after, fingerprint, transaction)
            }
        } else if (request.scopeId.isNotEmpty()) {
            chats.readAsGroupMember(uid, request.scopeId) { _, _ ->
                searchCurrent(uid, request, setOf(request.scopeId), after, fingerprint, null)
            }
        } else {
            chats.readAccessibleChatIds(uid) { scopes ->
                searchCurrent(uid, request, scopes, after, fingerprint, null)
            }
        }
    }

    private fun searchCurrent(
        uid: String,
        request: ContentSearchRequest,
        scopes: Set<String>,
        after: ContentAssetAnchor?,
        fingerprint: String,
        transaction: PgReadTransactionContext?,
    ): ContentSearchPage {
        if (scopes.isEmpty()) return ContentSearchPage(emptyList(), null)
        val result = ArrayList<ContentSearchHit>()
        var anchor = after
        repeat(4) {
            val candidates = index.search(request, scopes, anchor, 64)
            if (candidates.isEmpty()) return ContentSearchPage(result, null)
            val current = source.currentHits(request.kind, candidates.mapTo(hashSetOf()) { it.key.id })
            val readableDocuments = if (request.kind == ContentSearchRequest.KIND_DOCUMENT) {
                val snapshot = source.documentAccess(checkNotNull(transaction), uid, current.values.mapTo(hashSetOf()) { it.scopeId })
                snapshot.candidates.filter { candidate ->
                    DocumentAuthorizationPolicy.resolve(uid, candidate.space, candidate.grants,
                        snapshot.directUnitIds, snapshot.unitAndAncestorIds, DocumentCapability.READ).allowed
                }.mapTo(hashSetOf()) { it.space.spaceId }
            } else scopes
            candidates.forEachIndexed { position, candidate ->
                anchor = ContentAssetAnchor(candidate.updatedAt, candidate.key.id)
                val hit = current[candidate.key.id]
                if (hit != null && hit.scopeId == candidate.scopeId && hit.scopeId in scopes &&
                    hit.scopeId in readableDocuments && hit.revision == candidate.revision && hit.updatedAt == candidate.updatedAt &&
                    matchesCurrentFile(request, hit)) {
                    result += hit
                    if (result.size == request.limit) {
                        val more = position < candidates.lastIndex || candidates.size == 64
                        return ContentSearchPage(result, if (more) encodeCursor(fingerprint, checkNotNull(anchor)) else null)
                    }
                }
            }
            if (candidates.size < 64) return ContentSearchPage(result, null)
        }
        // Continuous mutations can leave sparse index candidates. Preserve a continuation instead
        // of scanning an unbounded index or claiming a partial empty page is the end of the search.
        return ContentSearchPage(result, anchor?.let { encodeCursor(fingerprint, it) })
    }

    private fun matchesCurrentFile(request: ContentSearchRequest, hit: ContentSearchHit): Boolean {
        if (request.kind != ContentSearchRequest.KIND_GROUP_FILE) return true
        if (!hit.title.contains(request.keyword, ignoreCase = true)) return false
        val mime = hit.mimeType.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        val type = when { mime.startsWith("image/") -> 1; mime.startsWith("video/") -> 2; mime.startsWith("audio/") -> 3; else -> 4 }
        return request.fileType == 0 || request.fileType == type
    }

    private fun encodeCursor(fingerprint: String, anchor: ContentAssetAnchor) = "s1:$fingerprint:${anchor.updatedAt}:${anchor.id}"
    private fun decodeCursor(cursor: String?, fingerprint: String): ContentAssetAnchor? {
        if (cursor == null) return null
        val parts = cursor.split(':')
        require(parts.size == 4 && parts[0] == "s1" && parts[1] == fingerprint) { "搜索游标与当前条件不一致" }
        val timestamp = parts[2].toLongOrNull()
        require(timestamp != null && timestamp >= 0 && parts[3].length in 1..36) { "搜索游标非法" }
        return ContentAssetAnchor(timestamp, parts[3])
    }
}
