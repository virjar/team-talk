package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.model.ContentSearchAttachments
import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.ContentSearchRpcProxy
import com.virjar.tk.protocol.rpc.gen.MessageRpcProxy
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ContentSearchInvalidation
import com.virjar.tk.shared.outcome
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** 打开搜索结果前，经当前领域 RPC 重新授权并解析出的对象。 */
sealed interface ResolvedContentSearchHit {
    data class Document(val document: com.virjar.tk.protocol.model.Document) : ResolvedContentSearchHit
    data class GroupFile(val entry: GroupFileEntry) : ResolvedContentSearchHit
    data class ChatMessage(val message: Message) : ResolvedContentSearchHit
}

/** 旧分页不能继续拼接；调用方观察 [ContentSearchRepository.changes] 后从首页重建当前查询。 */
class ContentSearchInvalidatedException : CancellationException("Content search result was invalidated")

/**
 * 有界远程搜索及打开解析。摘要不进入正文、消息或目录投影，也不成为下载描述符。
 * 查询编辑/页面销毁由 UI owner 负责；本层挡住 RPC 期间的领域变更与会话退役。
 */
class ContentSearchRepository(
    rpcClient: RpcInvoker,
    val changes: StateFlow<ContentSearchInvalidation>,
    private val documentRepository: DocumentRepository,
    private val groupFileRepository: GroupFileRepository,
    private val ensureActive: () -> Unit = {},
) {
    private val rpc = ContentSearchRpcProxy(rpcClient)
    private val messageRpc = MessageRpcProxy(rpcClient)

    suspend fun search(request: ContentSearchRequest): Outcome<ContentSearchPage> = outcome {
        stableRead(request.kind, retry = request.cursor == null) {
            val page = rpc.search(request)
            check(page.items.size <= request.limit) { "Content search page exceeded requested limit" }
            check(page.items.all { it.kind == request.kind && (request.scopeId.isEmpty() || it.scopeId == request.scopeId) }) {
                "Content search response escaped requested scope"
            }
            check(page.nextCursor == null || page.nextCursor != request.cursor) { "Content search cursor did not advance" }
            page
        }
    }

    suspend fun resolve(hit: ContentSearchHit): Outcome<ResolvedContentSearchHit> = outcome {
        stableRead(hit.kind, retry = true) {
            when (hit.kind) {
                ContentSearchRequest.KIND_DOCUMENT -> ResolvedContentSearchHit.Document(
                    documentRepository.getDocument(hit.scopeId, hit.targetId).getOrThrow(),
                )
                ContentSearchRequest.KIND_GROUP_FILE -> {
                    val entry = groupFileRepository.getEntry(hit.scopeId, hit.targetId).getOrThrow()
                    if (entry.chatId != hit.scopeId || entry.entryId != hit.targetId ||
                        entry.kind != GroupFileEntry.KIND_FILE || entry.attachment == null
                    ) throw unavailable()
                    ResolvedContentSearchHit.GroupFile(entry)
                }
                ContentSearchRequest.KIND_CHAT_ATTACHMENT -> {
                    // 这是独立的当前授权读取，不是已有聊天窗口的“更旧页”。复用历史 RPC
                    // 但不占用 MessageRepository 的分页链，也不为搜索重置驻留消息窗口。
                    val page = messageRpc.getHistory(hit.scopeId, hit.serverSeq, 1)
                    check(page.size <= 1) { "Content search message lookup exceeded requested limit" }
                    val message = page
                        .singleOrNull { it.chatId == hit.scopeId && it.serverSeq == hit.serverSeq }
                        ?: throw unavailable()
                    if (ContentSearchAttachments.attachments(message).none { attachment ->
                            val path = ContentSearchAttachments.canonicalPath(attachment)
                            MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it.toInt() and 0xff) } == hit.targetId
                        }
                    ) throw unavailable()
                    ResolvedContentSearchHit.ChatMessage(message)
                }
                else -> throw IllegalArgumentException("Unknown content search kind")
            }
        }
    }

    private suspend fun <T> stableRead(kind: Int, retry: Boolean, read: suspend () -> T): T {
        repeat(if (retry) 2 else 1) {
            ensureActive()
            val generation = changes.value.generation(kind)
            val result = read()
            ensureActive()
            if (changes.value.generation(kind) == generation) return result
        }
        throw ContentSearchInvalidatedException()
    }

    private fun unavailable() = AppError.Business(404, "内容不可访问或已被删除")
}
