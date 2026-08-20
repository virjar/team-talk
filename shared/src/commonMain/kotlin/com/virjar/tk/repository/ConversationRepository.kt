package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.model.Conversation
import com.virjar.tk.outcome
import com.virjar.tk.rpc.gen.ConversationRpcProxy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ConversationRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = ConversationRpcProxy(rpcClient)
    /**
     * 草稿 RPC 必须按客户端调用顺序完成。仅取消协程无法撤回已经发到服务器的请求，
     * 会让旧草稿晚于“清空草稿”落库；这里让已发请求在不可取消区间完成，再发送下一条。
     * 草稿频率很低，跨会话共用一条有序通道比平台各自维护脆弱的 latest-wins Job 更可靠。
     */
    private val draftMirrorMutex = Mutex()

    suspend fun listConversations(): Outcome<List<Conversation>> = outcome {
        rpc.list().also { list -> list.forEach { localCache.upsertConversation(it) } }
    }

    /**
     * 保存/清除草稿（null = 清除）。
     *
     * 本地立即生效（草稿是本地优先状态，服务端负责跨设备镜像）。
     * LocalCache 同时写入持久化 outbox，因此进程重建也能区分“未操作”
     * 与“明确清空”，不会让服务端旧草稿复活。
    */
    fun setDraftLocal(chatId: String, draft: String?): Long =
        localCache.setConversationDraft(chatId, draft)

    /**
     * 镜像指定的本地 generation。若它已被新输入取代，该任务直接成功退出；
     * RPC 成功后也只能条件确认同一 generation。
     */
    suspend fun mirrorDraft(chatId: String, generation: Long): Outcome<Unit> =
        draftMirrorMutex.withLock {
            val pending = localCache.getPendingConversationDrafts()
                .firstOrNull { it.chatId == chatId && it.generation == generation }
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
    suspend fun retryPendingDrafts(): Outcome<Unit> {
        val snapshot = localCache.getPendingConversationDrafts()
        for (pending in snapshot) {
            val result = mirrorDraft(pending.chatId, pending.generation)
            if (result is Outcome.Failure) return result
        }
        return Outcome.Success(Unit)
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
}
