package com.virjar.tk.domain.document

import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentHomeItem
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.UserRole
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 企业文档空间领域服务。
 *
 * 空间是独立权限根。用户授权与组织部门授权共同计算当前角色；文档和目录节点只属于空间，
 * 群聊既不拥有文档，也不参与权限判断。所有读写都重新计算实时组织归属。
 */
class DocumentService(
    private val repository: DocumentRepository,
    private val organizations: OrganizationRepository,
    private val users: UserStore,
) {
    private val logger = LoggerFactory.getLogger(DocumentService::class.java)

    fun listSpaces(actorUid: String): List<DocumentSpace> = resolveAccessibleSpaces(actorUid)

    fun createSpace(actorUid: String, name: String, description: String?): DocumentSpace {
        val actor = users.findByUid(actorUid) ?: throw IllegalArgumentException("用户不存在")
        require(actor.role == UserRole.HUMAN) { "服务账户不能创建文档空间" }
        val now = System.currentTimeMillis()
        val created = repository.createSpace(
            DocumentSpace(
                spaceId = UUID.randomUUID().toString(),
                name = validateSpaceName(name),
                description = validateDescription(description),
                myRole = DocumentSpace.ROLE_NONE,
                createdBy = actorUid,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return created.copy(myRole = DocumentSpace.ROLE_OWNER)
    }

    fun updateSpace(actorUid: String, spaceId: String, name: String, description: String?): DocumentSpace {
        val space = requireRole(actorUid, spaceId, DocumentSpace.ROLE_ADMIN)
        return repository.updateSpace(
            space.spaceId,
            validateSpaceName(name),
            validateDescription(description),
            System.currentTimeMillis(),
        ).copy(myRole = effectiveRole(actorUid, space))
    }

    fun archiveSpace(actorUid: String, spaceId: String) {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_OWNER)
        repository.archiveSpace(spaceId, System.currentTimeMillis())
    }

    fun listGrants(actorUid: String, spaceId: String): List<DocumentSpaceGrant> {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_ADMIN)
        val unitNames = organizations.listUnits().associate { it.unitId to it.name }
        return repository.listGrants(spaceId).map { grant ->
            grant.copy(
                displayName = when (grant.principalType) {
                    DocumentSpaceGrant.PRINCIPAL_USER -> users.findByUid(grant.principalId)?.name
                    DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT -> unitNames[grant.principalId]
                    else -> null
                },
            )
        }
    }

    fun upsertGrant(
        actorUid: String,
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
    ): DocumentSpaceGrant {
        val space = requireRole(actorUid, spaceId, DocumentSpace.ROLE_ADMIN)
        require(role in DocumentSpace.ROLE_VIEWER..DocumentSpace.ROLE_ADMIN) { "空间角色非法" }
        require(principalId.isNotBlank()) { "授权对象不能为空" }
        require(principalId != space.createdBy || principalType != DocumentSpaceGrant.PRINCIPAL_USER) {
            "空间所有者不需要重复授权"
        }
        val displayName = when (principalType) {
            DocumentSpaceGrant.PRINCIPAL_USER -> {
                val user = users.findByUid(principalId) ?: throw IllegalArgumentException("用户不存在")
                require(user.role == UserRole.HUMAN) { "不能向服务账户授予文档空间" }
                user.name
            }
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT ->
                organizations.findUnit(principalId)?.name ?: throw IllegalArgumentException("组织节点不存在")
            else -> throw IllegalArgumentException("授权对象类型非法")
        }
        val grant = DocumentSpaceGrant(
            spaceId = spaceId,
            principalType = principalType,
            principalId = principalId,
            role = role,
            includeDescendants = principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT && includeDescendants,
            displayName = displayName,
        )
        repository.upsertGrant(grant.copy(displayName = null))
        return grant
    }

    fun removeGrant(actorUid: String, spaceId: String, principalType: Int, principalId: String) {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_ADMIN)
        repository.removeGrant(spaceId, principalType, principalId)
    }

    fun listNodes(actorUid: String, spaceId: String, parentId: String?): List<DocumentNode> {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        requireParentFolder(spaceId, parentId)
        return repository.listNodes(spaceId, parentId)
    }

    fun createFolder(actorUid: String, spaceId: String, parentId: String?, name: String): DocumentNode {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_EDITOR)
        requireParentFolder(spaceId, parentId)
        val now = System.currentTimeMillis()
        return repository.createFolder(
            DocumentNode(
                nodeId = UUID.randomUUID().toString(),
                spaceId = spaceId,
                parentId = parentId,
                nodeType = DocumentNode.TYPE_FOLDER,
                name = validateNodeName(name),
                revision = 1,
                createdBy = actorUid,
                createdAt = now,
                updatedBy = actorUid,
                updatedAt = now,
            ),
        )
    }

    fun createDocument(
        actorUid: String,
        spaceId: String,
        parentId: String?,
        title: String,
        markdown: String,
    ): Document {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_EDITOR)
        requireParentFolder(spaceId, parentId)
        val now = System.currentTimeMillis()
        val document = Document(
            documentId = UUID.randomUUID().toString(),
            spaceId = spaceId,
            parentId = parentId,
            title = validateNodeName(title),
            markdown = validateMarkdown(markdown),
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
        )
        return repository.createDocument(
            document,
            DocumentRevision(document.documentId, 1, document.title, document.markdown, actorUid, now),
        )
    }

    fun getDocument(actorUid: String, spaceId: String, documentId: String): Document {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        val document = requireDocument(spaceId, documentId)
        try {
            repository.touchRecentDocument(actorUid, document.documentId, System.currentTimeMillis())
        } catch (error: Exception) {
            // 最近访问是个人索引，写入异常不得把已经授权且存在的正文伪装成读取失败。
            logger.warn("Failed to update document recent access uid={} documentId={}", actorUid, documentId, error)
        }
        return document
    }

    fun updateDocument(
        actorUid: String,
        spaceId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Document {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_EDITOR)
        requireDocument(spaceId, documentId)
        require(expectedRevision > 0) { "文档版本非法" }
        return repository.updateDocument(
            documentId,
            expectedRevision,
            validateNodeName(title),
            validateMarkdown(markdown),
            actorUid,
            System.currentTimeMillis(),
        )
    }

    fun moveNode(
        actorUid: String,
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
    ): DocumentNode {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_EDITOR)
        val node = requireNode(spaceId, nodeId)
        requireParentFolder(spaceId, parentId)
        require(parentId != nodeId) { "目录不能移动到自身" }
        if (node.nodeType == DocumentNode.TYPE_FOLDER && parentId != null) {
            var cursor: String? = parentId
            val visited = mutableSetOf<String>()
            while (cursor != null) {
                require(visited.add(cursor)) { "文档目录存在循环" }
                require(cursor != nodeId) { "目录不能移动到自己的下级目录" }
                cursor = requireNode(spaceId, cursor).parentId
            }
        }
        require(expectedRevision > 0) { "节点版本非法" }
        return repository.moveNode(
            nodeId,
            expectedRevision,
            parentId,
            validateNodeName(name),
            actorUid,
            System.currentTimeMillis(),
        )
    }

    fun deleteNode(actorUid: String, spaceId: String, nodeId: String, expectedRevision: Long) {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_EDITOR)
        val node = requireNode(spaceId, nodeId)
        if (node.nodeType == DocumentNode.TYPE_FOLDER) {
            require(repository.listNodes(spaceId, nodeId).isEmpty()) { "请先清空文件夹" }
        }
        require(expectedRevision > 0) { "节点版本非法" }
        repository.deleteNode(nodeId, expectedRevision, actorUid, System.currentTimeMillis())
    }

    fun listRevisions(actorUid: String, spaceId: String, documentId: String): List<DocumentRevisionSummary> {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        requireDocument(spaceId, documentId)
        return repository.listRevisions(documentId)
    }

    fun getRevision(
        actorUid: String,
        spaceId: String,
        documentId: String,
        revision: Long,
    ): DocumentRevision {
        requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        requireDocument(spaceId, documentId)
        require(revision > 0) { "文档版本非法" }
        return repository.findRevision(documentId, revision) ?: throw IllegalArgumentException("文档版本不存在")
    }

    fun listRecentDocuments(actorUid: String, limit: Int): List<DocumentHomeItem> {
        validateHomeLimit(limit)
        val spaces = accessibleSpaces(actorUid)
        if (spaces.isEmpty()) return emptyList()
        return repository.listRecentDocuments(actorUid, spaces.keys, limit).map(::toHomeItem)
    }

    fun listRecentlyCreatedDocuments(actorUid: String, limit: Int): List<DocumentHomeItem> {
        validateHomeLimit(limit)
        val spaces = accessibleSpaces(actorUid)
        if (spaces.isEmpty()) return emptyList()
        return repository.listRecentlyCreatedDocuments(spaces.keys, limit).map(::toHomeItem)
    }

    private fun requireRole(actorUid: String, spaceId: String, minimum: Int): DocumentSpace {
        val space = repository.findSpace(spaceId) ?: throw IllegalArgumentException("文档空间不存在")
        require(effectiveRole(actorUid, space) >= minimum) { "没有文档空间权限" }
        return space
    }

    private fun accessibleSpaces(actorUid: String): Map<String, DocumentSpace> =
        resolveAccessibleSpaces(actorUid).associateBy(DocumentSpace::spaceId)

    private fun toHomeItem(record: DocumentHomeRecord): DocumentHomeItem = DocumentHomeItem(
        documentId = record.documentId,
        spaceId = record.spaceId,
        spaceName = record.spaceName,
        title = record.title,
        excerpt = record.excerpt,
        createdBy = record.createdBy,
        creatorName = record.creatorName,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt,
        accessedAt = record.accessedAt,
    )

    private fun validateHomeLimit(limit: Int) {
        require(limit in 1..MAX_HOME_DOCUMENTS) { "首页文档数量必须在 1..$MAX_HOME_DOCUMENTS 之间" }
    }

    private fun resolveAccessibleSpaces(actorUid: String): List<DocumentSpace> {
        val access = actorAccess(actorUid)
        return repository.listSpaceAccessCandidates(
            actorUid = actorUid,
            directUnitIds = access.directUnitIds,
            unitAndAncestorIds = access.unitAndAncestorIds,
        ).mapNotNull { candidate ->
            effectiveRole(actorUid, candidate.space, candidate.grants, access)
                .takeIf { it >= DocumentSpace.ROLE_VIEWER }
                ?.let { candidate.space.copy(myRole = it) }
        }
    }

    private fun effectiveRole(actorUid: String, space: DocumentSpace): Int {
        if (space.createdBy == actorUid) return DocumentSpace.ROLE_OWNER
        return effectiveRole(actorUid, space, repository.listGrants(space.spaceId), actorAccess(actorUid))
    }

    private fun effectiveRole(
        actorUid: String,
        space: DocumentSpace,
        grants: List<DocumentSpaceGrant>,
        access: ActorAccess,
    ): Int {
        if (space.createdBy == actorUid) return DocumentSpace.ROLE_OWNER
        return grants.asSequence().filter { grant ->
            when (grant.principalType) {
                DocumentSpaceGrant.PRINCIPAL_USER -> grant.principalId == actorUid
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT -> if (grant.includeDescendants) {
                    grant.principalId in access.unitAndAncestorIds
                } else {
                    grant.principalId in access.directUnitIds
                }
                else -> false
            }
        }.maxOfOrNull { it.role } ?: DocumentSpace.ROLE_NONE
    }

    private fun actorAccess(actorUid: String): ActorAccess {
        val activeUnits = organizations.listUnits()
        val activeUnitIds = activeUnits.mapTo(hashSetOf()) { it.unitId }
        val directUnitIds = organizations.listMemberships(actorUid)
            .mapNotNullTo(linkedSetOf()) { membership ->
                membership.unitId.takeIf(activeUnitIds::contains)
            }
        if (directUnitIds.isEmpty()) return ActorAccess(emptySet(), emptySet())
        val parentByUnitId = activeUnits.associate { it.unitId to it.parentId }
        // 直属关系是独立事实；继承祖先必须来自一条完整无环的活动路径。
        val unitAndAncestorIds = linkedSetOf<String>().apply { addAll(directUnitIds) }
        directUnitIds.forEach { directId ->
            val inheritedPath = arrayListOf<String>()
            var cursor: String? = directId
            val visited = hashSetOf<String>()
            var cycleDetected = false
            while (cursor != null && cursor in activeUnitIds) {
                if (!visited.add(cursor)) {
                    cycleDetected = true
                    break
                }
                inheritedPath += cursor
                cursor = parentByUnitId[cursor]
            }
            if (!cycleDetected) unitAndAncestorIds += inheritedPath
        }
        return ActorAccess(directUnitIds, unitAndAncestorIds)
    }

    private fun requireParentFolder(spaceId: String, parentId: String?) {
        if (parentId == null) return
        val parent = requireNode(spaceId, parentId)
        require(parent.nodeType == DocumentNode.TYPE_FOLDER) { "父节点不是文件夹" }
    }

    private fun requireNode(spaceId: String, nodeId: String): DocumentNode {
        val node = repository.findNode(nodeId) ?: throw IllegalArgumentException("文档节点不存在")
        require(node.spaceId == spaceId) { "文档节点不属于当前空间" }
        return node
    }

    private fun requireDocument(spaceId: String, documentId: String): Document {
        val document = repository.findDocument(documentId) ?: throw IllegalArgumentException("文档不存在")
        require(document.spaceId == spaceId) { "文档不属于当前空间" }
        return document
    }

    private fun validateSpaceName(value: String): String = validateName(value, MAX_SPACE_NAME_LENGTH, "空间名称")
    private fun validateNodeName(value: String): String = validateName(value, MAX_NODE_NAME_LENGTH, "名称")

    private fun validateName(value: String, limit: Int, label: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "$label 不能为空" }
        require(name.length <= limit) { "$label 不能超过 $limit 个字符" }
        require(name.none { it.code < 32 }) { "$label 包含非法字符" }
        return name
    }

    private fun validateDescription(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
        require(it.length <= MAX_DESCRIPTION_LENGTH) { "空间说明不能超过 $MAX_DESCRIPTION_LENGTH 个字符" }
        require('\u0000' !in it) { "空间说明包含非法字符" }
    }

    private fun validateMarkdown(value: String): String {
        require(value.length <= MAX_MARKDOWN_LENGTH) { "文档正文不能超过 $MAX_MARKDOWN_LENGTH 个字符" }
        require('\u0000' !in value) { "文档正文包含非法字符" }
        DocumentMarkdownStructurePolicy.validate(
            markdown = value,
            maxQuoteDepth = MAX_MARKDOWN_QUOTE_DEPTH,
            maxTableColumns = MAX_MARKDOWN_TABLE_COLUMNS,
            maxTableCells = MAX_MARKDOWN_TABLE_CELLS,
            maxLines = MAX_MARKDOWN_LINES,
            maxRenderableBlocks = MAX_MARKDOWN_RENDERABLE_BLOCKS,
        )
        return value
    }

    private data class ActorAccess(
        val directUnitIds: Set<String>,
        val unitAndAncestorIds: Set<String>,
    )

    companion object {
        const val MAX_SPACE_NAME_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 500
        const val MAX_NODE_NAME_LENGTH = 180
        const val MAX_MARKDOWN_LENGTH = 1_000_000
        const val MAX_MARKDOWN_QUOTE_DEPTH = 64
        const val MAX_MARKDOWN_TABLE_COLUMNS = 32
        const val MAX_MARKDOWN_TABLE_CELLS = 1_000
        const val MAX_MARKDOWN_LINES = 20_000
        const val MAX_MARKDOWN_RENDERABLE_BLOCKS = 4_096
        const val MAX_HOME_DOCUMENTS = 50
    }
}
