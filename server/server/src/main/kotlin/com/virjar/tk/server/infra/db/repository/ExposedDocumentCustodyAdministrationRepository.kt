package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.domain.document.DocumentCustodyAdministrationPolicy
import com.virjar.tk.server.domain.document.DocumentCustodyAdministrationRepository
import com.virjar.tk.server.domain.document.DocumentCustodyBatchCommand
import com.virjar.tk.server.domain.document.DocumentCustodyBatchItem
import com.virjar.tk.server.domain.document.DocumentCustodyBatchReceipt
import com.virjar.tk.server.domain.document.DocumentCustodyGrantPlanEntry
import com.virjar.tk.server.domain.document.DocumentCustodyPlanConflictException
import com.virjar.tk.server.domain.document.DocumentCustodyPlanEntry
import com.virjar.tk.server.domain.document.DocumentCustodySnapshot
import com.virjar.tk.server.domain.document.DocumentCustodyTarget
import com.virjar.tk.server.domain.document.DocumentCustodyUserFact
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentCustodyBatchTransferItems
import com.virjar.tk.server.infra.db.DocumentCustodyBatchTransfers
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.OrganizationState
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** 显式管理 Document 资产交接控制平面的 PostgreSQL 适配器。 */
class ExposedDocumentCustodyAdministrationRepository : DocumentCustodyAdministrationRepository {
    override fun inspect(
        transaction: PgReadTransactionContext,
        sourceUid: String,
        target: DocumentCustodyTarget,
    ): DocumentCustodySnapshot = transaction.inExposedReadTransaction {
        val users = readUsers(setOf(sourceUid, target.stewardUid), lock = false)
        val directGrantSpaceIds = readDirectGrantSpaceIds(sourceUid, lock = false)
        DocumentCustodySnapshot(
            source = users[sourceUid],
            targetSteward = users[target.stewardUid],
            targetOwnerUnitStatus = readTargetUnitStatus(target, lock = false),
            spaces = readSourceSpaces(sourceUid),
            directGrants = readDirectGrantPlanEntries(directGrantSpaceIds),
        )
    }

    override fun transfer(
        transaction: PgWriteTransactionContext,
        command: DocumentCustodyBatchCommand,
        now: Long,
    ): DocumentCustodyBatchReceipt = transaction.inExposedTransaction {
        require(now >= 0L) { "文档资产交接时间非法" }

        // 固定顺序：OrganizationState -> 源/目标 Users(uid) -> Spaces(spaceId) ->
        // 目标 Unit(unitId) -> memberships/grants。普通单空间资产交接命令
        // 先取同一个单例，因此精确重试总能观察到其不可变批次回执。
        lockOrganizationState()
        readReceipt(command.operationId)?.let { receipt ->
            if (receipt.requestFingerprint != command.requestFingerprint) {
                throw ReliableCommandConflictException("文档资产批量交接操作标识已用于不同请求")
            }
            return@inExposedTransaction receipt.value
        }

        val users = readUsers(setOf(command.sourceUid, command.target.stewardUid), lock = true)

        // 先发现资产与授权两个根，再按字典序一次性锁定其拥有的 Space 行。
        // 用户授权插入已被源 User 行阻塞；并发的
        // 删除可能在这些锁之前完成，并会被最终的授权快照捕获。
        val discoveredSpaces = readSourceSpaces(command.sourceUid)
        val discoveredGrantSpaceIds = readDirectGrantSpaceIds(command.sourceUid, lock = false)
        val spaceIdsToLock = buildSet {
            discoveredSpaces.forEach { add(it.spaceId) }
            addAll(discoveredGrantSpaceIds)
        }
        val lockedSpaceRows = lockSpaces(spaceIdsToLock)
        val spaces = lockedSpaceRows.values
            .filter { row -> row.isSourceCustodySpace(command.sourceUid) }
            .map(ResultRow::toPlanEntry)
            .sortedBy(DocumentCustodyPlanEntry::spaceId)
        val targetUnitStatus = readTargetUnitStatus(command.target, lock = true)
        val directGrantSpaceIds = readDirectGrantSpaceIds(command.sourceUid, lock = true)
        val directGrants = directGrantSpaceIds.map { spaceId ->
            DocumentCustodyGrantPlanEntry(
                spaceId = spaceId,
                policyRevision = lockedSpaceRows.getValue(spaceId)[DocumentSpaces.policyRevision],
            )
        }
        val snapshot = DocumentCustodySnapshot(
            source = users[command.sourceUid],
            targetSteward = users[command.target.stewardUid],
            targetOwnerUnitStatus = targetUnitStatus,
            spaces = spaces,
            directGrants = directGrants,
        )
        DocumentCustodyAdministrationPolicy.requireValid(snapshot, command.target)

        val currentPlanFingerprint = DocumentCustodyAdministrationPolicy.fingerprint(
            command.sourceUid,
            command.target,
            spaces,
            directGrants,
        )
        if (currentPlanFingerprint != command.expectedPlanFingerprint) {
            throw DocumentCustodyPlanConflictException()
        }

        requireTargetOwnerCapacity(command.target, spaces)

        // 行已经按字典序锁定。这些是本批次事务内部的直接 CAS 更新，
        // 不是对普通单空间交接命令的调用。
        val items = spaces.map { space ->
            val resultingRevision = space.custodyRevision + 1L
            val updated = DocumentSpaces.update({
                (DocumentSpaces.spaceId eq space.spaceId) and
                    (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                    (DocumentSpaces.stewardUid eq command.sourceUid) and
                    (DocumentSpaces.custodyRevision eq space.custodyRevision)
            }) {
                it[ownerPrincipalType] = command.target.ownerPrincipalType
                it[ownerPrincipalId] = command.target.ownerPrincipalId
                it[stewardUid] = command.target.stewardUid
                it[custodyRevision] = resultingRevision
                it[updatedAt] = now
            }
            if (updated != 1) throw DocumentCustodyPlanConflictException()
            DocumentCustodyBatchItem(
                spaceId = space.spaceId,
                fromOwnerPrincipalType = space.ownerPrincipalType,
                fromOwnerPrincipalId = space.ownerPrincipalId,
                fromStewardUid = space.stewardUid,
                fromCustodyRevision = space.custodyRevision,
                toOwnerPrincipalType = command.target.ownerPrincipalType,
                toOwnerPrincipalId = command.target.ownerPrincipalId,
                toStewardUid = command.target.stewardUid,
                resultingCustodyRevision = resultingRevision,
            )
        }

        // Grant 行是最后的锁类别。清理刻意覆盖每个 Document
        // 空间，而不仅是交接清单。一次基于集合的 DELETE 使内存占用保持有界，
        // 并在不枚举授权行的情况下返回精确的审计计数。
        // 每个受影响的 Space 行都已在上面锁定，因此在此推进其 ACL CAS 不会获取
        // 更晚的锁类别，并且即使资产交接与授权清理重叠也恰好执行一次。
        val revokedGrantCount = DocumentSpaceGrants.deleteWhere {
            (principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                (principalId eq command.sourceUid)
        }
        if (revokedGrantCount != directGrantSpaceIds.size) {
            throw DocumentCustodyPlanConflictException()
        }
        if (directGrantSpaceIds.isNotEmpty()) {
            val exhaustedRevision = directGrantSpaceIds.firstOrNull { spaceId ->
                lockedSpaceRows.getValue(spaceId)[DocumentSpaces.policyRevision] == Long.MAX_VALUE
            }
            require(exhaustedRevision == null) {
                "文档空间权限版本已耗尽，无法撤销离职员工授权"
            }
            val revisedSpaces = DocumentSpaces.update({
                DocumentSpaces.spaceId inList directGrantSpaceIds
            }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[policyRevision] = policyRevision + 1L
                }
                it[updatedAt] = now
            }
            if (revisedSpaces != directGrantSpaceIds.size) {
                throw DocumentCustodyPlanConflictException()
            }
        }

        items.forEach { item ->
            DocumentCustodyBatchTransferItems.insert {
                it[operationId] = command.operationId
                it[spaceId] = item.spaceId
                it[fromOwnerPrincipalType] = item.fromOwnerPrincipalType
                it[fromOwnerPrincipalId] = item.fromOwnerPrincipalId
                it[fromStewardUid] = item.fromStewardUid
                it[fromCustodyRevision] = item.fromCustodyRevision
                it[toOwnerPrincipalType] = item.toOwnerPrincipalType
                it[toOwnerPrincipalId] = item.toOwnerPrincipalId
                it[toStewardUid] = item.toStewardUid
                it[resultingCustodyRevision] = item.resultingCustodyRevision
            }
        }
        DocumentCustodyBatchTransfers.insert {
            it[operationId] = command.operationId
            it[adminPrincipal] = command.adminPrincipal
            it[sourceUid] = command.sourceUid
            it[requestFingerprint] = command.requestFingerprint
            it[planFingerprint] = currentPlanFingerprint
            it[targetOwnerPrincipalType] = command.target.ownerPrincipalType
            it[targetOwnerPrincipalId] = command.target.ownerPrincipalId
            it[targetStewardUid] = command.target.stewardUid
            it[itemCount] = items.size
            it[DocumentCustodyBatchTransfers.revokedGrantCount] = revokedGrantCount
            it[createdAt] = now
        }
        if (items.isNotEmpty() || revokedGrantCount > 0) {
            // 批次回执与每个受影响的 Space/grant 行都在全局版本锁
            // 之前持久化。零工作回执与精确重放不会推进目录版本。
            ExposedDocumentDirectoryRevision.advance(transaction, now)
        }
        DocumentCustodyBatchReceipt(
            operationId = command.operationId,
            adminPrincipal = command.adminPrincipal,
            sourceUid = command.sourceUid,
            targetOwnerPrincipalType = command.target.ownerPrincipalType,
            targetOwnerPrincipalId = command.target.ownerPrincipalId,
            targetStewardUid = command.target.stewardUid,
            planFingerprint = currentPlanFingerprint,
            revokedGrantCount = revokedGrantCount,
            createdAt = now,
            items = items,
        )
    }

    private fun lockOrganizationState() {
        val lockedId = OrganizationState.selectAll().where {
            OrganizationState.id eq ORGANIZATION_STATE_SINGLETON_ID
        }.forUpdate().singleOrNull()?.get(OrganizationState.id)
        check(lockedId == ORGANIZATION_STATE_SINGLETON_ID) {
            "Organization state singleton is missing"
        }
    }

    private fun readUsers(
        userIds: Set<String>,
        lock: Boolean,
    ): Map<String, DocumentCustodyUserFact> {
        if (userIds.isEmpty()) return emptyMap()
        val query = Users.selectAll().where { Users.uid inList userIds.sorted() }
            .orderBy(Users.uid to SortOrder.ASC)
        val rows = if (lock) query.forUpdate().toList() else query.toList()
        return rows.associate { row ->
            val fact = DocumentCustodyUserFact(
                uid = row[Users.uid],
                role = row[Users.role],
                status = row[Users.status],
            )
            fact.uid to fact
        }
    }

    private fun readSourceSpaces(sourceUid: String): List<DocumentCustodyPlanEntry> {
        val query = DocumentSpaces.selectAll().where {
            (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                (
                    (DocumentSpaces.stewardUid eq sourceUid) or
                        (
                            (DocumentSpaces.ownerPrincipalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                                (DocumentSpaces.ownerPrincipalId eq sourceUid)
                            )
                    )
        }.orderBy(DocumentSpaces.spaceId to SortOrder.ASC)
            .limit(DocumentCapacityPolicy.ACTIVE_STEWARDSHIP_OVERFLOW_PROBE_LIMIT)
        return query.map(ResultRow::toPlanEntry)
    }

    private fun readDirectGrantSpaceIds(sourceUid: String, lock: Boolean): List<String> {
        val query = DocumentSpaceGrants.selectAll().where {
            (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                (DocumentSpaceGrants.principalId eq sourceUid)
        }.orderBy(
            DocumentSpaceGrants.spaceId to SortOrder.ASC,
            DocumentSpaceGrants.id to SortOrder.ASC,
        ).limit(DocumentCapacityPolicy.DIRECT_USER_GRANT_OVERFLOW_PROBE_LIMIT)
        val rows = if (lock) query.forUpdate().toList() else query.toList()
        return rows.map { it[DocumentSpaceGrants.spaceId] }
    }

    private fun readDirectGrantPlanEntries(spaceIds: List<String>): List<DocumentCustodyGrantPlanEntry> {
        if (spaceIds.isEmpty()) return emptyList()
        val policyRevisions = DocumentSpaces.select(
            DocumentSpaces.spaceId,
            DocumentSpaces.policyRevision,
        ).where {
            DocumentSpaces.spaceId inList spaceIds
        }.associate { row ->
            row[DocumentSpaces.spaceId] to row[DocumentSpaces.policyRevision]
        }
        check(policyRevisions.size == spaceIds.size) { "Document grant references a missing space" }
        return spaceIds.map { spaceId ->
            DocumentCustodyGrantPlanEntry(spaceId, policyRevisions.getValue(spaceId))
        }
    }

    private fun lockSpaces(spaceIds: Set<String>): Map<String, ResultRow> {
        if (spaceIds.isEmpty()) return emptyMap()
        val rows = DocumentSpaces.selectAll().where {
            DocumentSpaces.spaceId inList spaceIds.sorted()
        }.orderBy(DocumentSpaces.spaceId to SortOrder.ASC).forUpdate().toList()
        check(rows.size == spaceIds.size) { "Document grant references a missing space" }
        return rows.associateBy { it[DocumentSpaces.spaceId] }
    }

    private fun readTargetUnitStatus(target: DocumentCustodyTarget, lock: Boolean): Int? {
        if (target.ownerPrincipalType != DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) return null
        val query = OrganizationUnits.selectAll().where {
            OrganizationUnits.unitId eq target.ownerPrincipalId
        }.orderBy(OrganizationUnits.unitId to SortOrder.ASC)
        val row = if (lock) query.forUpdate().singleOrNull() else query.singleOrNull()
        return row?.get(OrganizationUnits.status)
    }

    private fun requireTargetOwnerCapacity(
        target: DocumentCustodyTarget,
        spaces: List<DocumentCustodyPlanEntry>,
    ) {
        val currentOwnedCount = DocumentSpaces.select(DocumentSpaces.spaceId).where {
            (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentSpaces.ownerPrincipalType eq target.ownerPrincipalType) and
                (DocumentSpaces.ownerPrincipalId eq target.ownerPrincipalId)
        }.limit(DocumentCapacityPolicy.ACTIVE_SPACE_OVERFLOW_PROBE_LIMIT).count()
        val newlyOwnedCount = spaces.count { space ->
            space.ownerPrincipalType != target.ownerPrincipalType ||
                space.ownerPrincipalId != target.ownerPrincipalId
        }
        val resultingCount = currentOwnedCount + newlyOwnedCount.toLong()
        require(resultingCount <= DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER.toLong()) {
            "目标所有者活动文档空间数量将超过 ${DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER}"
        }

        val currentStewardshipCount = DocumentSpaces.select(DocumentSpaces.spaceId).where {
            (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentSpaces.stewardUid eq target.stewardUid)
        }.limit(DocumentCapacityPolicy.ACTIVE_STEWARDSHIP_OVERFLOW_PROBE_LIMIT).count()
        val newlyStewardedCount = spaces.count { it.stewardUid != target.stewardUid }
        val resultingStewardshipCount = currentStewardshipCount + newlyStewardedCount.toLong()
        require(
            resultingStewardshipCount <= DocumentCapacityPolicy.MAX_ACTIVE_STEWARDSHIPS_PER_USER.toLong(),
        ) {
            "目标责任人活动文档空间数量将超过 ${DocumentCapacityPolicy.MAX_ACTIVE_STEWARDSHIPS_PER_USER}"
        }
    }

    private fun readReceipt(operationId: String): StoredReceipt? {
        val row = DocumentCustodyBatchTransfers.selectAll().where {
            DocumentCustodyBatchTransfers.operationId eq operationId
        }.singleOrNull() ?: return null
        val items = DocumentCustodyBatchTransferItems.selectAll().where {
            DocumentCustodyBatchTransferItems.operationId eq operationId
        }.orderBy(DocumentCustodyBatchTransferItems.spaceId to SortOrder.ASC)
            .map(ResultRow::toBatchItem)
        check(items.size == row[DocumentCustodyBatchTransfers.itemCount]) {
            "Document custody batch receipt is incomplete"
        }
        return StoredReceipt(
            requestFingerprint = row[DocumentCustodyBatchTransfers.requestFingerprint],
            value = DocumentCustodyBatchReceipt(
                operationId = row[DocumentCustodyBatchTransfers.operationId],
                adminPrincipal = row[DocumentCustodyBatchTransfers.adminPrincipal],
                sourceUid = row[DocumentCustodyBatchTransfers.sourceUid],
                targetOwnerPrincipalType = row[DocumentCustodyBatchTransfers.targetOwnerPrincipalType],
                targetOwnerPrincipalId = row[DocumentCustodyBatchTransfers.targetOwnerPrincipalId],
                targetStewardUid = row[DocumentCustodyBatchTransfers.targetStewardUid],
                planFingerprint = row[DocumentCustodyBatchTransfers.planFingerprint],
                revokedGrantCount = row[DocumentCustodyBatchTransfers.revokedGrantCount],
                createdAt = row[DocumentCustodyBatchTransfers.createdAt],
                items = items,
            ),
        )
    }

    private data class StoredReceipt(
        val requestFingerprint: String,
        val value: DocumentCustodyBatchReceipt,
    )

    private companion object {
        const val ORGANIZATION_STATE_SINGLETON_ID = 1
    }
}

private fun ResultRow.toPlanEntry() = DocumentCustodyPlanEntry(
    spaceId = this[DocumentSpaces.spaceId],
    name = this[DocumentSpaces.name],
    ownerPrincipalType = this[DocumentSpaces.ownerPrincipalType],
    ownerPrincipalId = this[DocumentSpaces.ownerPrincipalId],
    stewardUid = this[DocumentSpaces.stewardUid],
    custodyRevision = this[DocumentSpaces.custodyRevision],
    policyRevision = this[DocumentSpaces.policyRevision],
)

private fun ResultRow.isSourceCustodySpace(sourceUid: String): Boolean =
    this[DocumentSpaces.status] == DOCUMENT_STATUS_ACTIVE &&
        (
            this[DocumentSpaces.stewardUid] == sourceUid ||
                (
                    this[DocumentSpaces.ownerPrincipalType] == DocumentSpaceGrant.PRINCIPAL_USER &&
                        this[DocumentSpaces.ownerPrincipalId] == sourceUid
                    )
            )

private fun ResultRow.toBatchItem() = DocumentCustodyBatchItem(
    spaceId = this[DocumentCustodyBatchTransferItems.spaceId],
    fromOwnerPrincipalType = this[DocumentCustodyBatchTransferItems.fromOwnerPrincipalType],
    fromOwnerPrincipalId = this[DocumentCustodyBatchTransferItems.fromOwnerPrincipalId],
    fromStewardUid = this[DocumentCustodyBatchTransferItems.fromStewardUid],
    fromCustodyRevision = this[DocumentCustodyBatchTransferItems.fromCustodyRevision],
    toOwnerPrincipalType = this[DocumentCustodyBatchTransferItems.toOwnerPrincipalType],
    toOwnerPrincipalId = this[DocumentCustodyBatchTransferItems.toOwnerPrincipalId],
    toStewardUid = this[DocumentCustodyBatchTransferItems.toStewardUid],
    resultingCustodyRevision = this[DocumentCustodyBatchTransferItems.resultingCustodyRevision],
)
