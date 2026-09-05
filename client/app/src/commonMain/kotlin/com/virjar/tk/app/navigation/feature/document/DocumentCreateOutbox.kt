package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import java.util.UUID

/**
 * 保留到服务器确认稳定客户端资源 ID 为止的不可变创建载荷。
 *
 * 载荷刻意在第一次准入时冻结。如果服务器已提交但响应丢失，每一次 retry
 * 都必须复现相同的指纹，即使用户仍在继续编辑存活的 tab。
 */
internal data class PendingDocumentCreateCommand(
    val documentId: String,
    val tabInstanceId: Long,
    val spaceId: String,
    val parentId: String?,
    val title: String,
    val markdown: String,
    val admittedEditGeneration: Long,
    val assets: List<EmbeddedAsset> = emptyList(),
) {
    fun normalized(): PendingDocumentCreateCommand? {
        val canonicalDocumentId = documentId.canonicalUuidOrNull() ?: return null
        val canonicalSpaceId = spaceId.canonicalUuidOrNull() ?: return null
        val canonicalParentId = parentId?.canonicalUuidOrNull() ?: parentId?.let { return null }
        val normalizedTitle = runCatching { DocumentPolicy.normalizeNodeName(title) }.getOrNull()
            ?: return null
        if (tabInstanceId <= 0L || admittedEditGeneration < 0L) return null
        if (runCatching { DocumentPolicy.validateMarkdownEnvelope(markdown) }.isFailure) return null
        val canonicalAssets = runCatching { MarkdownAssetPolicy.canonicalize(markdown, assets) }.getOrNull()
            ?: return null
        return copy(
            documentId = canonicalDocumentId,
            spaceId = canonicalSpaceId,
            parentId = canonicalParentId,
            title = normalizedTitle,
            assets = canonicalAssets,
        )
    }

    fun matches(tab: DocumentTabState): Boolean =
        tab.creating && tab.documentId == null && tab.revision == null &&
            tab.tabId == documentId && tab.instanceId == tabInstanceId &&
            tab.spaceId == spaceId && tab.parentId == parentId &&
            tab.editGeneration >= admittedEditGeneration

    companion object {
        fun capture(tab: DocumentTabState): PendingDocumentCreateCommand? {
            if (!tab.creating || tab.documentId != null || tab.revision != null) return null
            return PendingDocumentCreateCommand(
                documentId = tab.tabId,
                tabInstanceId = tab.instanceId,
                spaceId = tab.spaceId,
                parentId = tab.parentId,
                title = tab.draftTitle,
                markdown = tab.draftMarkdown,
                assets = tab.draftAssets,
                admittedEditGeneration = tab.editGeneration,
            ).normalized()
        }
    }
}

internal data class PendingDocumentCreateReplay(
    val command: PendingDocumentCreateCommand,
    val tab: DocumentTabState,
)

/**
 * Session 拥有的、线程安全的、镜像到草稿快照中的创建命令内存索引。
 * 不同空间/文档的创建可以并发进行；相同的意图复用一个稳定 ID。
 */
internal class DocumentDurableCreateOutbox(
    private val newSpaceId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val spacesByIntent = linkedMapOf<DocumentSpaceCreateIntent, DocumentSpaceCreateRequest>()
    private val documentsById = linkedMapOf<String, PendingDocumentCreateCommand>()

    /** 预期的容量拒绝在 UI 回调进入 [acquireSpace] 之前检查。 */
    fun canAcquireSpace(name: String, description: String?): Boolean = synchronized(lock) {
        val intent = DocumentSpaceCreateIntent(name, description)
        intent in spacesByIntent || spacesByIntent.size < MAX_PENDING_CREATES
    }

    fun willAcquireNewSpace(name: String, description: String?): Boolean = synchronized(lock) {
        DocumentSpaceCreateIntent(name, description) !in spacesByIntent
    }

    fun acquireSpace(name: String, description: String?): DocumentSpaceCreateRequest = synchronized(lock) {
        val intent = DocumentSpaceCreateIntent(
            name = name.trim().also { require(it.isNotEmpty()) { "文档空间名称不能为空" } },
            description = description?.trim()?.takeIf(String::isNotEmpty),
        )
        spacesByIntent[intent] ?: run {
            check(spacesByIntent.size < MAX_PENDING_CREATES) { "待创建文档空间过多" }
            val request = DocumentSpaceCreateRequest(intent, newSpaceId())
            requireNotNull(request.normalized()) { "文档空间创建标识必须是规范 UUID" }
                .also { spacesByIntent[intent] = it }
        }
    }

    fun completeSpace(request: DocumentSpaceCreateRequest): Boolean = synchronized(lock) {
        if (spacesByIntent[request.intent] != request) return@synchronized false
        spacesByIntent.remove(request.intent)
        true
    }

    fun discardSpace(request: DocumentSpaceCreateRequest): Boolean = synchronized(lock) {
        if (spacesByIntent[request.intent] != request) return@synchronized false
        spacesByIntent.remove(request.intent)
        true
    }

    fun acquireDocument(tab: DocumentTabState): PendingDocumentCreateCommand = synchronized(lock) {
        documentsById[tab.tabId]?.let { existing ->
            check(existing.matches(tab)) { "文档创建标识已属于其他本地草稿" }
            return@synchronized existing
        }
        check(documentsById.size < MAX_PENDING_CREATES) { "待创建文档过多" }
        requireNotNull(PendingDocumentCreateCommand.capture(tab)) { "文档草稿不满足创建条件" }
            .also { documentsById[it.documentId] = it }
    }

    fun completeDocument(command: PendingDocumentCreateCommand): Boolean = synchronized(lock) {
        if (documentsById[command.documentId] != command) return@synchronized false
        documentsById.remove(command.documentId)
        true
    }

    fun discardDocument(tab: DocumentTabState): Boolean = synchronized(lock) {
        val command = documentsById[tab.tabId] ?: return@synchronized false
        if (command.tabInstanceId != tab.instanceId) return@synchronized false
        documentsById.remove(tab.tabId)
        true
    }

    fun discardDocumentsInSpace(spaceId: String): Boolean = synchronized(lock) {
        val ids = documentsById.values.filter { it.spaceId == spaceId }.map { it.documentId }
        ids.forEach(documentsById::remove)
        ids.isNotEmpty()
    }

    fun containsDocument(command: PendingDocumentCreateCommand): Boolean = synchronized(lock) {
        documentsById[command.documentId] == command
    }

    fun restore(
        spaces: List<DocumentSpaceCreateRequest>,
        documents: List<PendingDocumentCreateCommand>,
    ) = synchronized(lock) {
        spaces.take(MAX_PENDING_CREATES).forEach { request ->
            request.normalized()?.let { spacesByIntent.putIfAbsent(it.intent, it) }
        }
        documents.take(MAX_PENDING_CREATES).forEach { command ->
            command.normalized()?.let { documentsById.putIfAbsent(it.documentId, it) }
        }
    }

    fun pendingSpaces(): List<DocumentSpaceCreateRequest> = synchronized(lock) {
        spacesByIntent.values.toList()
    }

    fun pendingDocuments(): List<PendingDocumentCreateCommand> = synchronized(lock) {
        documentsById.values.toList()
    }

    fun replayableDocuments(
        tabs: List<DocumentTabState>,
        availableSpaceIds: Set<String>,
    ): List<PendingDocumentCreateReplay> = synchronized(lock) {
        documentsById.values.mapNotNull { command ->
            if (command.spaceId !in availableSpaceIds) return@mapNotNull null
            tabs.firstOrNull(command::matches)?.let { tab ->
                PendingDocumentCreateReplay(command, tab)
            }
        }
    }

    private companion object {
        const val MAX_PENDING_CREATES = 512
    }
}

private fun String.canonicalUuidOrNull(): String? = try {
    UUID.fromString(this).toString().takeIf { it == this }
} catch (_: IllegalArgumentException) {
    null
}
