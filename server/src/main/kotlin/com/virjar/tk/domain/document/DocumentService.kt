package com.virjar.tk.domain.document

import com.virjar.tk.domain.chat.ActiveChatMembership
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary
import java.util.UUID

/**
 * 协作文档领域服务。
 *
 * v1 只开放群聊作用域；权限不固化到文档，而是在每次读写时根据当前群成员资格判断。
 * 客户端提交的 expectedRevision 是唯一覆盖坐标，服务端不会静默合并或覆盖并发修改。
 */
class DocumentService(
    private val repository: DocumentRepository,
    private val chats: ChatRepository,
    private val memberships: ActiveChatMembership,
) {
    fun list(actorUid: String, scopeType: Int, scopeId: String): List<DocumentSummary> {
        requireScopeAccess(actorUid, scopeType, scopeId)
        return repository.list(scopeType, scopeId)
    }

    fun get(actorUid: String, scopeType: Int, scopeId: String, documentId: String): Document {
        requireScopeAccess(actorUid, scopeType, scopeId)
        return requireDocument(scopeType, scopeId, documentId)
    }

    fun create(actorUid: String, scopeType: Int, scopeId: String, title: String, markdown: String): Document {
        requireScopeAccess(actorUid, scopeType, scopeId)
        val validTitle = validateTitle(title)
        val validMarkdown = validateMarkdown(markdown)
        val now = System.currentTimeMillis()
        val document = Document(
            documentId = UUID.randomUUID().toString(),
            scopeType = scopeType,
            scopeId = scopeId,
            title = validTitle,
            markdown = validMarkdown,
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
        )
        return repository.create(
            document,
            DocumentRevision(
                documentId = document.documentId,
                revision = document.revision,
                title = document.title,
                markdown = document.markdown,
                editedBy = actorUid,
                editedAt = now,
            ),
        )
    }

    fun update(
        actorUid: String,
        scopeType: Int,
        scopeId: String,
        documentId: String,
        title: String,
        markdown: String,
        expectedRevision: Long,
    ): Document {
        requireScopeAccess(actorUid, scopeType, scopeId)
        requireDocument(scopeType, scopeId, documentId)
        require(expectedRevision > 0) { "文档版本非法" }
        return repository.update(
            documentId = documentId,
            expectedRevision = expectedRevision,
            title = validateTitle(title),
            markdown = validateMarkdown(markdown),
            actorUid = actorUid,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun listRevisions(
        actorUid: String,
        scopeType: Int,
        scopeId: String,
        documentId: String,
    ): List<DocumentRevisionSummary> {
        requireScopeAccess(actorUid, scopeType, scopeId)
        requireDocument(scopeType, scopeId, documentId)
        return repository.listRevisions(documentId)
    }

    fun getRevision(
        actorUid: String,
        scopeType: Int,
        scopeId: String,
        documentId: String,
        revision: Long,
    ): DocumentRevision {
        requireScopeAccess(actorUid, scopeType, scopeId)
        requireDocument(scopeType, scopeId, documentId)
        require(revision > 0) { "文档版本非法" }
        return repository.findRevision(documentId, revision)
            ?: throw IllegalArgumentException("文档版本不存在")
    }

    fun delete(actorUid: String, scopeType: Int, scopeId: String, documentId: String, expectedRevision: Long) {
        requireScopeAccess(actorUid, scopeType, scopeId)
        requireDocument(scopeType, scopeId, documentId)
        require(expectedRevision > 0) { "文档版本非法" }
        repository.delete(documentId, expectedRevision, actorUid, System.currentTimeMillis())
    }

    private fun requireScopeAccess(actorUid: String, scopeType: Int, scopeId: String) {
        require(scopeType == Document.SCOPE_GROUP_CHAT) { "暂不支持该文档空间" }
        val chat = chats.getChat(scopeId)
        require(chat != null && chat.chatType == ChatType.GROUP.code) { "群聊不存在" }
        require(scopeId in memberships.listUserChatIds(actorUid)) { "你不是当前群成员" }
    }

    private fun requireDocument(scopeType: Int, scopeId: String, documentId: String): Document {
        val document = repository.find(documentId) ?: throw IllegalArgumentException("文档不存在")
        require(document.scopeType == scopeType && document.scopeId == scopeId) { "文档不属于当前空间" }
        return document
    }

    private fun validateTitle(value: String): String {
        val title = value.trim()
        require(title.isNotEmpty()) { "文档标题不能为空" }
        require(title.length <= MAX_TITLE_LENGTH) { "文档标题不能超过 $MAX_TITLE_LENGTH 个字符" }
        require(title.none { it.code < 32 }) { "文档标题包含非法字符" }
        return title
    }

    private fun validateMarkdown(value: String): String {
        require(value.length <= MAX_MARKDOWN_LENGTH) { "文档正文不能超过 $MAX_MARKDOWN_LENGTH 个字符" }
        require('\u0000' !in value) { "文档正文包含非法字符" }
        return value
    }

    companion object {
        const val MAX_TITLE_LENGTH = 180
        const val MAX_MARKDOWN_LENGTH = 1_000_000
    }
}
