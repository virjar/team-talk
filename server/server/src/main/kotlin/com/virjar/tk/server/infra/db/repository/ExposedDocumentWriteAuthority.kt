package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentWriteAuthority
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.toUserAvatar
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** 一个文档空间写授权快照的 PostgreSQL 锁协议。 */
internal object ExposedDocumentWriteAuthority {
    fun lock(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredOrganizationUnitIds: Set<String>,
        requiredUserIds: Set<String>,
    ): DocumentWriteAuthority {
        transaction.requireExposedTransaction()

        // 用户状态是与凭据封禁共享的准入 fence。始终按字典序先锁定 actor 和
        // 每个用户值命令目标，再触及空间或 ACL；
        // 因此封禁要么完全在普通 Document 写之前线性化，要么完全在其之后。
        // 在 actor 通过受保护的空间策略之前，缺失的目标保持不披露。
        val requestedUserIds = (requiredUserIds + actorUid).sorted()
        val lockedUsers = Users.selectAll().where {
            Users.uid inList requestedUserIds
        }.orderBy(Users.uid to SortOrder.ASC).forUpdate()
            .associate { row -> row[Users.uid] to row.toDocumentAclUser() }

        val space = DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
        }.forUpdate().singleOrNull()?.toDocumentSpace()
            ?: throw DocumentNotFoundException("文档空间不存在")
        // Grant 变更先取同一空间行，因此这些行形成一个稳定的 ACL
        // 快照。锁定它们作为防御，防止仍有遗留写入器直接
        // 寻址表而不是通过此仓库端口进入。
        val grants = DocumentSpaceGrants.selectAll().where {
            DocumentSpaceGrants.spaceId eq spaceId
        }.orderBy(
            DocumentSpaceGrants.principalType to SortOrder.ASC,
            DocumentSpaceGrants.principalId to SortOrder.ASC,
        ).limit(DocumentSpaceGrant.MAX_GRANTS_PER_SPACE + 1)
            .forUpdate().map(ResultRow::toDocumentSpaceGrant)
        require(grants.size <= DocumentSpaceGrant.MAX_GRANTS_PER_SPACE) {
            "文档空间授权数量超过限制"
        }
        if (space.stewardUid == actorUid && requiredOrganizationUnitIds.isEmpty()) {
            return DocumentWriteAuthority(
                actor = lockedUsers[actorUid],
                space = space,
                grants = grants,
                directUnitIds = emptySet(),
                unitAndAncestorIds = emptySet(),
                missingRequiredOrganizationUnitIds = emptySet(),
                missingRequiredUserIds = requiredUserIds - lockedUsers.keys,
            )
        }

        // 不加行锁发现成员资格。组织写入器在 Memberships 之前锁定 Units，
        // 因此在此取 Membership 锁会颠倒其顺序。我们先锁定
        // 发现的 Unit 路径，再锁成员行，并在下面比较此快照。
        val discoveredDirectMembershipUnitIds = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.uid eq actorUid
        }.orderBy(
            OrganizationMemberships.unitId to SortOrder.ASC,
            OrganizationMemberships.id to SortOrder.ASC,
        ).map { it[OrganizationMemberships.unitId] }.distinct()

        // 只在不加锁的情况下发现 actor 的当前路径，然后按同一字典序获取每个被引用的 unit
        // 行。在跟随历史环时按 child->parent 加锁，可能会使两个直接成员资格
        // 从该环相反两侧开始的写入器死锁。
        // 在有序加锁之后，将锁定的事实与发现结果比较，若
        // 组织变更在两个阶段之间竞态发生则 fail closed。
        val observedUnits = hashMapOf<String, OrganizationAuthorityRow?>()
        fun observeUnit(unitId: String): OrganizationAuthorityRow? {
            if (observedUnits.containsKey(unitId)) return observedUnits[unitId]
            val row = OrganizationUnits.selectAll().where {
                OrganizationUnits.unitId eq unitId
            }.singleOrNull()?.toOrganizationAuthorityRow()
            observedUnits[unitId] = row
            return row
        }

        val authorityUnitIds = linkedSetOf<String>()
        requiredOrganizationUnitIds.sorted().forEach { unitId ->
            authorityUnitIds += unitId
            observeUnit(unitId)
        }
        discoveredDirectMembershipUnitIds.sorted().forEach { directId ->
            var cursor: String? = directId
            val visited = hashSetOf<String>()
            while (cursor != null && visited.add(cursor)) {
                authorityUnitIds += cursor
                val unit = observeUnit(cursor) ?: break
                if (unit.status != OrganizationUnit.STATUS_ACTIVE) break
                cursor = unit.parentId
            }
        }

        val lockedUnits = if (authorityUnitIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationUnits.selectAll().where {
                OrganizationUnits.unitId inList authorityUnitIds.sorted()
            }.orderBy(OrganizationUnits.unitId to SortOrder.ASC).forUpdate()
                .associate { row -> row[OrganizationUnits.unitId] to row.toOrganizationAuthorityRow() }
        }
        authorityUnitIds.forEach { unitId ->
            require(lockedUnits[unitId] == observedUnits[unitId]) {
                "组织权限正在变更，请重试"
            }
        }

        // Unit -> Membership 与组织命令共享。竞态移除可能使
        // 锁定集在发现之后缩小；每条剩余路径都已锁定，因此领域可以
        // 安全地重新评估缩减后的授权，并在适当时拒绝该 actor。竞态
        // 插入可能引入一条其 Unit 行从未锁定的路径，必须 fail closed。
        // 一旦这些行被锁定，之后任何成员变更都线性化在此写之后。
        val directMembershipUnitIds = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.uid eq actorUid
        }.orderBy(
            OrganizationMemberships.unitId to SortOrder.ASC,
            OrganizationMemberships.id to SortOrder.ASC,
        ).forUpdate().map { it[OrganizationMemberships.unitId] }.distinct()
        require(discoveredDirectMembershipUnitIds.containsAll(directMembershipUnitIds)) {
            "组织权限正在变更，请重试"
        }

        val directUnitIds = linkedSetOf<String>()
        val unitAndAncestorIds = linkedSetOf<String>()
        directMembershipUnitIds.sorted().forEach { directId ->
            val direct = lockedUnits[directId]
            if (direct == null || direct.status != OrganizationUnit.STATUS_ACTIVE) return@forEach
            directUnitIds += directId

            val inheritedPath = arrayListOf<String>()
            val visited = hashSetOf<String>()
            var cursor: String? = directId
            var cycleDetected = false
            while (cursor != null) {
                if (!visited.add(cursor)) {
                    cycleDetected = true
                    break
                }
                val unit = lockedUnits[cursor] ?: break
                if (unit.status != OrganizationUnit.STATUS_ACTIVE) break
                inheritedPath += cursor
                cursor = unit.parentId
            }
            // 即使历史组织数据包含环，直接活跃成员资格仍保持权威。
            // 继承的授权需要一条完整的无环路径。
            if (!cycleDetected) unitAndAncestorIds += inheritedPath
        }
        unitAndAncestorIds += directUnitIds

        return DocumentWriteAuthority(
            actor = lockedUsers[actorUid],
            space = space,
            grants = grants,
            directUnitIds = directUnitIds,
            unitAndAncestorIds = unitAndAncestorIds,
            missingRequiredOrganizationUnitIds = requiredOrganizationUnitIds - lockedUnits.keys,
            missingRequiredUserIds = requiredUserIds - lockedUsers.keys,
        )
    }

    private const val STATUS_ACTIVE = 1
}

private data class OrganizationAuthorityRow(
    val parentId: String?,
    val status: Int,
)

private fun ResultRow.toOrganizationAuthorityRow() = OrganizationAuthorityRow(
    parentId = this[OrganizationUnits.parentId],
    status = this[OrganizationUnits.status],
)

internal fun ResultRow.toDocumentAclUser() = User(
    uid = this[Users.uid],
    username = this[Users.username],
    name = this[Users.name],
    avatar = toUserAvatar(),
    phone = this[Users.phone],
    sex = this[Users.sex],
    role = this[Users.role],
    status = this[Users.status],
    revision = this[Users.revision],
)
