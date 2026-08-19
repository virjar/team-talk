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
     * 本地立即生效（草稿是纯客户端状态，服务端只是跨设备镜像）：清除若只等
     * CONVERSATION_UPDATED 回环，会被本地缓存「draft 非空优先」合并策略挡回，
     * 表现为发送后列表仍显示 [草稿] 且重进会话回填旧草稿。
    */
    fun setDraftLocal(chatId: String, draft: String?) {
        localCache.setConversationDraft(chatId, draft)
    }

    /** 仅镜像到服务端；同一客户端的镜像严格有序，调用者不得取消旧请求来冒充 latest-wins。 */
    suspend fun mirrorDraft(chatId: String, draft: String?): Outcome<Unit> = outcome {
        draftMirrorMutex.withLock {
            withContext(NonCancellable) {
                rpc.setDraft(chatId, draft)
            }
        }
    }

    suspend fun setDraft(chatId: String, draft: String?): Outcome<Unit> {
        // 草稿首先是本地状态：断网时也必须立即保留。服务端只负责跨设备镜像，
        // 不能让 RPC 失败阻断本地落盘。
        setDraftLocal(chatId, draft)
        return mirrorDraft(chatId, draft)
    }
    suspend fun setPin(chatId: String, pinned: Boolean): Outcome<Unit> = outcome { rpc.setPin(chatId, pinned) }
    suspend fun setMute(chatId: String, muted: Boolean): Outcome<Unit> = outcome { rpc.setMute(chatId, muted) }
    suspend fun deleteConversation(chatId: String): Outcome<Unit> = outcome {
        rpc.delete(chatId)
        localCache.deleteConversation(chatId)
    }
}
