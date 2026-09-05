package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.ConversationPageRequest
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.gen.ConversationRpcProxy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ConversationRepository internal constructor(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
    private val onPendingMirrorCommitted: () -> Unit,
) {
    constructor(rpcClient: RpcInvoker, localCache: LocalCache) : this(
        rpcClient = rpcClient,
        localCache = localCache,
        onPendingMirrorCommitted = {},
    )

    private val rpc = ConversationRpcProxy(rpcClient)
    /**
     * LocalCache 的快照代次是独占的，因此同一会话的两次全量刷新
     * 不能让它们的 begin/collect/apply 窗口重叠。这个互斥锁刻意只覆盖
     * 权威快照路径；草稿镜像在下面保留自己独立的有序通道。
     */
    private val snapshotMutex = Mutex()

    /**
     * 草稿 RPC 必须按客户端调用顺序完成。仅取消协程无法撤回已经发到服务器的请求，
     * 会让旧草稿晚于“清空草稿”落库；这里让已发请求在不可取消区间完成，再发送下一条。
     * 草稿频率很低，跨会话共用一条有序通道比平台各自维护脆弱的 latest-wins Job 更可靠。
     */
    private val draftMirrorMutex = Mutex()

    suspend fun listConversations(): Outcome<List<Conversation>> = snapshotMutex.withLock {
        outcome {
            repeat(MAX_SNAPSHOT_ATTEMPTS) {
                // 必须在每次 RPC 发出前建立边界；否则请求期间到达的 Notify
                // 无法与旧响应区分。
                val snapshotGeneration = localCache.beginConversationSnapshot()
                val remote = collectSnapshotPages()
                if (localCache.applyConversationSnapshot(snapshotGeneration, remote)) {
                    // 调用方必须看到收敛后的本地投影，不能把原始 RPC 响应直接渲染出去。
                    return@outcome localCache.getConversations()
                }
            }
            throw IllegalStateException(
                "Conversation snapshot stayed conflicted after $MAX_SNAPSHOT_ATTEMPTS attempts",
            )
        }
    }

    /**
     * 保存/清除草稿（null = 清除）。
     *
     * 本地立即生效（草稿是本地优先状态，服务端负责跨设备镜像）。
     * LocalCache 同时写入持久化 outbox，因此进程重建也能区分“未操作”
     * 与“明确清空”，不会让服务端旧草稿复活。
    */
    fun setDraftLocal(chatId: String, draft: String?): Long {
        val generation = localCache.setConversationDraft(chatId, draft)
        onPendingMirrorCommitted()
        return generation
    }

    /**
     * 镜像指定的本地 generation。若它已被新输入取代，该任务直接成功退出；
     * RPC 成功后也只能条件确认同一 generation。
     */
    suspend fun mirrorDraft(chatId: String, generation: Long): Outcome<Unit> =
        draftMirrorMutex.withLock {
            val pending = localCache.getPendingConversationDraft(chatId)
                ?.takeIf { it.generation == generation }
                ?: return@withLock Outcome.Success(Unit)
            outcome {
                // 已发出的请求不能被调用方取消后让后续操作越过。
                // 服务端也按 uid 串行 setDraft，两端共同保证到达顺序。
                withContext(NonCancellable) {
                    rpc.setDraft(chatId, pending.draft)
                }
                localCache.markConversationDraftMirrored(chatId, generation)
            }
        }

    /** 启动和每次认证恢复后重试未获得成功应答的持久化操作。 */
    suspend fun retryPendingDrafts(): Outcome<Unit> = retryPendingMirrors(
        snapshot = localCache.getPendingConversationDrafts(),
    ) { pending ->
        mirrorDraft(pending.chatId, pending.generation)
    }

    suspend fun setDraft(chatId: String, draft: String?): Outcome<Unit> {
        // 草稿首先是本地状态：断网时也必须立即保留。服务端只负责跨设备镜像，
        // 不能让 RPC 失败阻断本地落盘。
        val generation = setDraftLocal(chatId, draft)
        return mirrorDraft(chatId, generation)
    }
    suspend fun setPin(chatId: String, pinned: Boolean): Outcome<Unit> = outcome { rpc.setPin(chatId, pinned) }
    suspend fun setMute(chatId: String, muted: Boolean): Outcome<Unit> = outcome { rpc.setMute(chatId, muted) }
    suspend fun deleteConversation(chatId: String): Outcome<Unit> = outcome {
        rpc.delete(chatId)
        localCache.deleteConversation(chatId)
    }

    /**
     * 收集一份逻辑权威快照，且不发布部分页面。调用方建立的 LocalCache
     * 代次会把与任何分页请求重叠的事件都挡在栅栏外。
     */
    private suspend fun collectSnapshotPages(): List<Conversation> {
        val collected = ArrayList<Conversation>()
        val seenChatIds = HashSet<String>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null
        var totalTextCharacters = 0L
        var totalDraftCharacters = 0L
        var pageCount = 0

        while (true) {
            if (pageCount >= MAX_SNAPSHOT_PAGES) {
                throw IllegalStateException(
                    "Conversation snapshot exceeded $MAX_SNAPSHOT_CONVERSATIONS entries",
                )
            }
            pageCount += 1
            val page = rpc.listPage(ConversationPageRequest(cursor))
            if (page.items.size > MAX_SNAPSHOT_CONVERSATIONS - collected.size) {
                throw IllegalStateException(
                    "Conversation snapshot exceeded $MAX_SNAPSHOT_CONVERSATIONS entries",
                )
            }
            page.items.forEach { conversation ->
                if (!seenChatIds.add(conversation.chatId)) {
                    throw IllegalStateException("Conversation snapshot repeated a chatId")
                }
                val draftCharacters = conversation.draft?.length?.toLong() ?: 0L
                if (draftCharacters > MAX_SNAPSHOT_DRAFT_CHARACTERS - totalDraftCharacters) {
                    throw IllegalStateException(
                        "Conversation snapshot exceeded the aggregate draft budget",
                    )
                }
                totalDraftCharacters += draftCharacters
                val itemTextCharacters = conversation.textCharacterCount()
                if (itemTextCharacters > MAX_SNAPSHOT_TEXT_CHARACTERS - totalTextCharacters) {
                    throw IllegalStateException(
                        "Conversation snapshot exceeded the text budget",
                    )
                }
                totalTextCharacters += itemTextCharacters
                collected += conversation
            }

            val nextCursor = page.nextCursor ?: return collected
            if (nextCursor == cursor || !seenCursors.add(nextCursor)) {
                throw IllegalStateException("Conversation snapshot cursor did not advance")
            }
            cursor = nextCursor
        }
    }

    private companion object {
        const val MAX_SNAPSHOT_ATTEMPTS = 3
        const val MAX_SNAPSHOT_CONVERSATIONS =
            ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER
        const val MAX_SNAPSHOT_TEXT_CHARACTERS =
            ConversationCapacityPolicy.MAX_SNAPSHOT_TEXT_CHARACTERS
        const val MAX_SNAPSHOT_DRAFT_CHARACTERS =
            ConversationCapacityPolicy.MAX_TOTAL_DRAFT_CHARACTERS_PER_USER
        const val MAX_SNAPSHOT_PAGES =
            (MAX_SNAPSHOT_CONVERSATIONS + ConversationPage.MAX_PAGE_SIZE - 1) /
                ConversationPage.MAX_PAGE_SIZE
    }
}

private fun Conversation.textCharacterCount(): Long =
    chatId.length.toLong() +
        (peerUid?.length ?: 0) +
        (chatName?.length ?: 0) +
        (chatAvatar?.let { it.path.length + it.name.length + it.contentType.length } ?: 0) +
        (lastMessage?.length ?: 0) +
        (draft?.length ?: 0)
