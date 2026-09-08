package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentReadAccessSnapshot
import com.virjar.tk.server.domain.document.DocumentSpaceAccessCandidate
import com.virjar.tk.server.domain.search.*
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.infra.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedContentAssetRepository(private val database: Database) : ContentAssetRepository {
    override fun pending(limit: Int): List<ContentAssetPending> = transaction(database) {
        require(limit in 1..8)
        ContentSearchPending.selectAll().orderBy(ContentSearchPending.kind to SortOrder.ASC, ContentSearchPending.resourceId to SortOrder.ASC)
            .limit(limit).map { ContentAssetPending(ContentAssetKey(it[ContentSearchPending.kind], it[ContentSearchPending.resourceId]), it[ContentSearchPending.revision]) }
    }

    override fun acknowledge(pending: ContentAssetPending) = transaction(database) {
        ContentSearchPending.deleteWhere {
            (kind eq pending.key.kind) and (resourceId eq pending.key.id) and (revision eq pending.revision)
        }
        Unit
    }

    override fun projection(key: ContentAssetKey): ContentAssetProjection? = transaction(database) {
        when (key.kind) {
            1 -> DocumentNodes.selectAll().where { DocumentNodes.nodeId eq key.id }.singleOrNull()?.documentProjection()
            2 -> GroupFileEntries.selectAll().where {
                (GroupFileEntries.entryId eq key.id) and (GroupFileEntries.kind eq GroupFileEntry.KIND_FILE)
            }.singleOrNull()?.groupProjection()
            else -> error("Unknown asset kind")
        }
    }

    override fun scan(kind: Int, afterId: String?, limit: Int): List<ContentAssetProjection> = transaction(database) {
        // A document is at most 3 MB UTF-8. Eight current bodies keep rebuild pages below 24 MB.
        require(limit in 1..8)
        when (kind) {
            1 -> DocumentNodes.selectAll().where { if (afterId == null) Op.TRUE else DocumentNodes.nodeId greater afterId }
                .orderBy(DocumentNodes.nodeId).limit(limit).map { it.documentProjection() }
            2 -> GroupFileEntries.selectAll().where {
                (GroupFileEntries.kind eq GroupFileEntry.KIND_FILE) and
                    (if (afterId == null) Op.TRUE else GroupFileEntries.entryId greater afterId)
            }.orderBy(GroupFileEntries.entryId).limit(limit).map { it.groupProjection() }
            else -> error("Unknown asset kind")
        }
    }

    override fun documentScopes(transaction: PgReadTransactionContext, uid: String, scopeId: String): Set<String> = transaction.inExposedReadTransaction {
        val actor = Users.select(Users.uid).where {
            (Users.uid eq uid) and (Users.status eq 1) and (Users.role eq UserRole.HUMAN)
        }.singleOrNull() ?: throw DocumentAccessDeniedException("当前账号不能读取文档")
        check(actor[Users.uid] == uid)
        val access = ExposedDocumentActorAccess.read(transaction, uid)
        val grant = relevantDocumentGrantCondition(uid, access)
        val ids = DocumentSpaces.join(DocumentSpaceGrants, JoinType.LEFT,
            DocumentSpaces.spaceId, DocumentSpaceGrants.spaceId)
            .select(DocumentSpaces.spaceId).where {
                (DocumentSpaces.status eq 1) and ((DocumentSpaces.stewardUid eq uid) or grant) and
                    (if (scopeId.isEmpty()) Op.TRUE else DocumentSpaces.spaceId eq scopeId)
            }.withDistinct().orderBy(DocumentSpaces.spaceId).limit(MAX_SEARCH_SCOPES + 1)
            .mapTo(linkedSetOf()) { it[DocumentSpaces.spaceId] }
        require(ids.size <= MAX_SEARCH_SCOPES) { "可搜索空间超过上限，请指定空间" }
        if (scopeId.isNotEmpty() && ids.isEmpty()) throw DocumentAccessDeniedException("文档空间不可访问")
        ids
    }

    override fun documentAccess(transaction: PgReadTransactionContext, uid: String, spaceIds: Set<String>): DocumentReadAccessSnapshot = transaction.inExposedReadTransaction {
        require(spaceIds.size <= 100)
        val access = ExposedDocumentActorAccess.read(transaction, uid)
        val spaces = if (spaceIds.isEmpty()) emptyList() else DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId inList spaceIds) and (DocumentSpaces.status eq 1)
        }.map { it.toDocumentSpace() }
        val grants = if (spaceIds.isEmpty()) emptyMap() else DocumentSpaceGrants.selectAll().where {
            (DocumentSpaceGrants.spaceId inList spaceIds) and relevantDocumentGrantCondition(uid, access)
        }.limit(spaceIds.size * DocumentSpaceGrant.MAX_GRANTS_PER_SPACE + 1).map { it.toDocumentSpaceGrant() }
            .groupBy { it.spaceId }
        require(grants.values.all { it.size <= DocumentSpaceGrant.MAX_GRANTS_PER_SPACE })
        DocumentReadAccessSnapshot(spaces.map { DocumentSpaceAccessCandidate(it, grants[it.spaceId].orEmpty()) }, access.directUnitIds, access.unitAndAncestorIds)
    }

    override fun currentHits(kind: Int, ids: Set<String>): Map<String, ContentSearchHit> = transaction(database) {
        require(ids.size <= 100)
        if (ids.isEmpty()) return@transaction emptyMap()
        when (kind) {
            1 -> DocumentNodes.join(DocumentSpaces, JoinType.INNER, DocumentNodes.spaceId, DocumentSpaces.spaceId)
                .select(DocumentNodes.nodeId, DocumentNodes.spaceId, DocumentNodes.name, DocumentNodes.excerpt,
                    DocumentNodes.revision, DocumentNodes.updatedAt, DocumentSpaces.name)
                .where { (DocumentNodes.nodeId inList ids) and (DocumentNodes.status eq 1) and (DocumentSpaces.status eq 1) }
                .associate { row -> row[DocumentNodes.nodeId] to ContentSearchHit(1, row[DocumentNodes.spaceId], row[DocumentNodes.nodeId],
                    row[DocumentNodes.name], row[DocumentNodes.excerpt], row[DocumentSpaces.name], row[DocumentNodes.revision], row[DocumentNodes.updatedAt]) }
            2 -> GroupFileEntries.join(GroupChats, JoinType.INNER, GroupFileEntries.chatId, GroupChats.chatId)
                .join(Chats, JoinType.INNER, GroupFileEntries.chatId, Chats.chatId)
                .select(GroupFileEntries.columns + listOf(GroupChats.name))
                .where { (GroupFileEntries.entryId inList ids) and (GroupFileEntries.status eq 1) and
                    (GroupFileEntries.kind eq GroupFileEntry.KIND_FILE) and (Chats.status eq 1) }
                .associate { row -> row[GroupFileEntries.entryId] to ContentSearchHit(2, row[GroupFileEntries.chatId], row[GroupFileEntries.entryId],
                    row[GroupFileEntries.name], "", row[GroupChats.name], row[GroupFileEntries.revision], row[GroupFileEntries.updatedAt],
                    checkNotNull(row[GroupFileEntries.attachmentContentType]), checkNotNull(row[GroupFileEntries.attachmentSize])) }
            else -> error("Unknown asset kind")
        }
    }

    private fun ResultRow.documentProjection(): ContentAssetProjection {
        val active = this[DocumentNodes.status] == 1
        return ContentAssetProjection(ContentAssetKey(1, this[DocumentNodes.nodeId]), this[DocumentNodes.spaceId], this[DocumentNodes.revision],
            this[DocumentNodes.updatedAt], active, if (active) this[DocumentNodes.name] else "", if (active) this[DocumentNodes.markdown] else "")
    }

    private fun ResultRow.groupProjection(): ContentAssetProjection {
        val active = this[GroupFileEntries.status] == 1
        return ContentAssetProjection(ContentAssetKey(2, this[GroupFileEntries.entryId]), this[GroupFileEntries.chatId], this[GroupFileEntries.revision],
            this[GroupFileEntries.updatedAt], active, if (active) this[GroupFileEntries.name] else "", "", this[GroupFileEntries.attachmentContentType])
    }

    companion object { const val MAX_SEARCH_SCOPES = 10_000 }
}
