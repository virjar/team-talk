package com.virjar.tk.domain.document

import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentHomeItem
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.UserRole
import kotlinx.coroutines.CancellationException
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
    private val unitOfWork: PgUnitOfWork,
) {
    private val logger = LoggerFactory.getLogger(DocumentService::class.java)
    private val accessControl = DocumentAccessControl(repository, organizations, unitOfWork)

    fun listSpaces(actorUid: String): List<DocumentSpace> = accessControl.resolveAccessibleSpaces(actorUid)

    suspend fun createSpace(actorUid: String, name: String, description: String?): DocumentSpace {
        val validatedName = validateSpaceName(name)
        val validatedDescription = validateDescription(description)
        val now = System.currentTimeMillis()
        return unitOfWork.write {
            val actor = repository.findUser(transaction, actorUid)
                ?: throw IllegalArgumentException("用户不存在")
            require(actor.role == UserRole.HUMAN) { "服务账户不能创建文档空间" }
            repository.createSpace(
                transaction,
                DocumentSpace(
                    spaceId = UUID.randomUUID().toString(),
                    name = validatedName,
                    description = validatedDescription,
                    myRole = DocumentSpace.ROLE_NONE,
                    createdBy = actorUid,
                    createdAt = now,
                    updatedAt = now,
                ),
            ).copy(myRole = DocumentSpace.ROLE_OWNER)
        }
    }

    suspend fun updateSpace(actorUid: String, spaceId: String, name: String, description: String?): DocumentSpace {
        val validatedName = validateSpaceName(name)
        val validatedDescription = validateDescription(description)
        return accessControl.writeAuthorized(actorUid, spaceId, DocumentSpace.ROLE_ADMIN) { space, role ->
            repository.updateSpace(
                transaction,
                space.spaceId,
                validatedName,
                validatedDescription,
                System.currentTimeMillis(),
            ).copy(myRole = role)
        }
    }

    suspend fun archiveSpace(actorUid: String, spaceId: String) {
        accessControl.writeAuthorized(actorUid, spaceId, DocumentSpace.ROLE_OWNER) { _, _ ->
            repository.archiveSpace(transaction, spaceId, System.currentTimeMillis())
        }
    }

    fun listGrants(actorUid: String, spaceId: String): List<DocumentSpaceGrant> {
        accessControl.requireRole(actorUid, spaceId, DocumentSpace.ROLE_ADMIN)
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

    suspend fun upsertGrant(
        actorUid: String,
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
    ): DocumentSpaceGrant {
        require(role in DocumentSpace.ROLE_VIEWER..DocumentSpace.ROLE_ADMIN) { "空间角色非法" }
        require(principalId.isNotBlank()) { "授权对象不能为空" }
        require(
            principalType == DocumentSpaceGrant.PRINCIPAL_USER ||
                principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
        ) { "授权对象类型非法" }
        val requiredOrganizationUnitIds = if (
            principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT
        ) {
            setOf(principalId)
        } else {
            emptySet()
        }
        val requiredUserIds = if (principalType == DocumentSpaceGrant.PRINCIPAL_USER) {
            setOf(principalId)
        } else {
            emptySet()
        }
        return accessControl.writeAuthorized(
            actorUid = actorUid,
            spaceId = spaceId,
            minimum = DocumentSpace.ROLE_ADMIN,
            requiredOrganizationUnitIds = requiredOrganizationUnitIds,
            requiredUserIds = requiredUserIds,
        ) { space, _ ->
            require(principalId != space.createdBy || principalType != DocumentSpaceGrant.PRINCIPAL_USER) {
                "空间所有者不需要重复授权"
            }
            val displayName = when (principalType) {
                DocumentSpaceGrant.PRINCIPAL_USER -> {
                    val user = repository.findUser(transaction, principalId)
                        ?: throw IllegalArgumentException("用户不存在")
                    require(user.role == UserRole.HUMAN) { "不能向服务账户授予文档空间" }
                    user.name
                }
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT ->
                    repository.findActiveOrganizationUnitName(transaction, principalId)
                        ?: throw IllegalArgumentException("组织节点不存在")
                else -> error("principalType was validated before transaction admission")
            }
            val grant = DocumentSpaceGrant(
                spaceId = spaceId,
                principalType = principalType,
                principalId = principalId,
                role = role,
                includeDescendants =
                    principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT && includeDescendants,
                displayName = displayName,
            )
            repository.upsertGrant(transaction, grant.copy(displayName = null))
            grant
        }
    }

    suspend fun removeGrant(actorUid: String, spaceId: String, principalType: Int, principalId: String) {
        accessControl.writeAuthorized(actorUid, spaceId, DocumentSpace.ROLE_ADMIN) { _, _ ->
            repository.removeGrant(transaction, spaceId, principalType, principalId)
        }
    }

    fun listNodes(actorUid: String, spaceId: String, parentId: String?): List<DocumentNode> {
        accessControl.requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        requireParentFolder(spaceId, parentId)
        return repository.listNodes(spaceId, parentId)
    }

    suspend fun createFolder(actorUid: String, spaceId: String, parentId: String?, name: String): DocumentNode {
        val validatedName = validateNodeName(name)
        val now = System.currentTimeMillis()
        return accessControl.writeAuthorized(actorUid, spaceId, DocumentSpace.ROLE_EDITOR) { _, _ ->
            requireParentFolder(transaction, spaceId, parentId)
            repository.createFolder(
                transaction,
                DocumentNode(
                    nodeId = UUID.randomUUID().toString(),
                    spaceId = spaceId,
                    parentId = parentId,
                    nodeType = DocumentNode.TYPE_FOLDER,
                    name = validatedName,
                    revision = 1,
                    createdBy = actorUid,
                    createdAt = now,
                    updatedBy = actorUid,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun createDocument(
        actorUid: String,
        spaceId: String,
        parentId: String?,
        title: String,
        markdown: String,
    ): Document {
        val validatedTitle = validateNodeName(title)
        val validatedMarkdown = validateMarkdown(markdown)
        val now = System.currentTimeMillis()
        val document = Document(
            documentId = UUID.randomUUID().toString(),
            spaceId = spaceId,
            parentId = parentId,
            title = validatedTitle,
            markdown = validatedMarkdown,
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
        )
        val created = accessControl.writeAuthorized(
            actorUid,
            spaceId,
            DocumentSpace.ROLE_EDITOR,
        ) { _, _ ->
            requireParentFolder(transaction, spaceId, parentId)
            repository.createDocument(
                transaction,
                document,
                DocumentRevision(document.documentId, 1, document.title, document.markdown, actorUid, now),
            )
        }
        touchRecentBestEffort(actorUid, created.documentId, now)
        return created
    }

    suspend fun getDocument(actorUid: String, spaceId: String, documentId: String): Document {
        accessControl.requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        val document = requireDocument(spaceId, documentId)
        touchRecentBestEffort(actorUid, document.documentId, System.currentTimeMillis())
        return document
    }

    private suspend fun touchRecentBestEffort(actorUid: String, documentId: String, accessedAt: Long) {
        try {
            unitOfWork.write {
                repository.touchRecentDocument(transaction, actorUid, documentId, accessedAt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 最近访问是个人索引，写入异常不得把已经授权且存在的正文伪装成读取失败。
            logger.warn("Failed to update document recent access uid={} documentId={}", actorUid, documentId, error)
        }
    }

    suspend fun updateDocument(
        actorUid: String,
        spaceId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Document {
        require(expectedRevision > 0) { "文档版本非法" }
        val validatedTitle = validateNodeName(title)
        val validatedMarkdown = validateMarkdown(markdown)
        return accessControl.writeAuthorized(actorUid, spaceId, DocumentSpace.ROLE_EDITOR) { _, _ ->
            requireDocument(transaction, spaceId, documentId)
            repository.updateDocument(
                transaction,
                spaceId,
                documentId,
                expectedRevision,
                validatedTitle,
                validatedMarkdown,
                actorUid,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun moveNode(
        actorUid: String,
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
    ): DocumentNode {
        require(expectedRevision > 0) { "节点版本非法" }
        val validatedName = validateNodeName(name)
        return accessControl.writeAuthorized(actorUid, spaceId, DocumentSpace.ROLE_EDITOR) { _, _ ->
            val node = requireNode(transaction, spaceId, nodeId)
            requireParentFolder(transaction, spaceId, parentId)
            require(parentId != nodeId) { "目录不能移动到自身" }
            if (node.nodeType == DocumentNode.TYPE_FOLDER && parentId != null) {
                var cursor: String? = parentId
                val visited = mutableSetOf<String>()
                while (cursor != null) {
                    require(visited.add(cursor)) { "文档目录存在循环" }
                    require(cursor != nodeId) { "目录不能移动到自己的下级目录" }
                    cursor = requireNode(transaction, spaceId, cursor).parentId
                }
            }
            repository.moveNode(
                transaction,
                spaceId,
                nodeId,
                expectedRevision,
                parentId,
                validatedName,
                actorUid,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun deleteNode(actorUid: String, spaceId: String, nodeId: String, expectedRevision: Long) {
        require(expectedRevision > 0) { "节点版本非法" }
        accessControl.writeAuthorized(actorUid, spaceId, DocumentSpace.ROLE_EDITOR) { _, _ ->
            val node = requireNode(transaction, spaceId, nodeId)
            if (node.nodeType == DocumentNode.TYPE_FOLDER) {
                require(repository.listNodes(transaction, spaceId, nodeId).isEmpty()) { "请先清空文件夹" }
            }
            repository.deleteNode(
                transaction,
                spaceId,
                nodeId,
                expectedRevision,
                actorUid,
                System.currentTimeMillis(),
            )
        }
    }

    fun listRevisions(actorUid: String, spaceId: String, documentId: String): List<DocumentRevisionSummary> {
        accessControl.requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        requireDocument(spaceId, documentId)
        return repository.listRevisions(documentId)
    }

    fun getRevision(
        actorUid: String,
        spaceId: String,
        documentId: String,
        revision: Long,
    ): DocumentRevision {
        accessControl.requireRole(actorUid, spaceId, DocumentSpace.ROLE_VIEWER)
        requireDocument(spaceId, documentId)
        require(revision > 0) { "文档版本非法" }
        return repository.findRevision(documentId, revision) ?: throw IllegalArgumentException("文档版本不存在")
    }

    fun listRecentDocuments(actorUid: String, limit: Int): List<DocumentHomeItem> {
        validateHomeLimit(limit)
        val spaces = accessControl.accessibleSpaces(actorUid)
        if (spaces.isEmpty()) return emptyList()
        return repository.listRecentDocuments(actorUid, spaces.keys, limit).map(::toHomeItem)
    }

    fun listRecentlyCreatedDocuments(actorUid: String, limit: Int): List<DocumentHomeItem> {
        validateHomeLimit(limit)
        val spaces = accessControl.accessibleSpaces(actorUid)
        if (spaces.isEmpty()) return emptyList()
        return repository.listRecentlyCreatedDocuments(spaces.keys, limit).map(::toHomeItem)
    }

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

    private fun requireParentFolder(spaceId: String, parentId: String?) {
        if (parentId == null) return
        val parent = requireNode(spaceId, parentId)
        require(parent.nodeType == DocumentNode.TYPE_FOLDER) { "父节点不是文件夹" }
    }

    private fun requireParentFolder(
        transaction: PgTransactionContext,
        spaceId: String,
        parentId: String?,
    ) {
        if (parentId == null) return
        val parent = requireNode(transaction, spaceId, parentId)
        require(parent.nodeType == DocumentNode.TYPE_FOLDER) { "父节点不是文件夹" }
    }

    private fun requireNode(spaceId: String, nodeId: String): DocumentNode {
        val node = repository.findNode(nodeId) ?: throw IllegalArgumentException("文档节点不存在")
        require(node.spaceId == spaceId) { "文档节点不属于当前空间" }
        return node
    }

    private fun requireNode(
        transaction: PgTransactionContext,
        spaceId: String,
        nodeId: String,
    ): DocumentNode {
        val node = repository.findNode(transaction, nodeId)
            ?: throw IllegalArgumentException("文档节点不存在")
        require(node.spaceId == spaceId) { "文档节点不属于当前空间" }
        return node
    }

    private fun requireDocument(spaceId: String, documentId: String): Document {
        val document = repository.findDocument(documentId) ?: throw IllegalArgumentException("文档不存在")
        require(document.spaceId == spaceId) { "文档不属于当前空间" }
        return document
    }

    private fun requireDocument(
        transaction: PgTransactionContext,
        spaceId: String,
        documentId: String,
    ): Document {
        val document = repository.findDocument(transaction, documentId)
            ?: throw IllegalArgumentException("文档不存在")
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
