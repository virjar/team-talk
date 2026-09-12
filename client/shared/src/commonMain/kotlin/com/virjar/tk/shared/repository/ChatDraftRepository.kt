package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.model.ChatDraftSnapshot as SharedChatDraftSnapshot
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.RpcStatusException
import com.virjar.tk.protocol.rpc.gen.ChatDraftRpcProxy
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.LocalChatDraftSync
import com.virjar.tk.shared.outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 完整草稿的 CAS 同步。SQLite 拥有未确认操作，页面和重连只唤醒同一个恢复通道。 */
class ChatDraftRepository(
    rpcClient: RpcInvoker,
    private val cache: LocalCache,
    private val onPendingCommitted: () -> Unit = {},
) {
    val local: LocalChatDraftSync = cache.chatDraftSync
    private val rpc = ChatDraftRpcProxy(rpcClient)
    private val requests = Mutex()

    suspend fun refresh(chatId: String): Outcome<Unit> = withContext(Dispatchers.IO) {
        outcome { requests.withLock { refreshLocked(chatId) }; onPendingCommitted() }
    }
    private suspend fun refreshLocked(chatId: String): SharedChatDraftSnapshot {
        local.ensure(chatId)
        val snapshot = try { rpc.get(chatId) } catch (failure: Exception) {
            if (status(failure) in listOf(403, 404)) local.readFailed(chatId, "此会话已不可访问，本机草稿仍保留")
            throw failure
        }
        require(snapshot.chatId == chatId)
        if (!local.applyRemote(snapshot)) throw AppError.Business(503, "草稿在读取期间已变化，将重新读取")
        return snapshot
    }
    suspend fun keepLocal(chatId: String): Outcome<Unit> = resolve(chatId, true)
    suspend fun useRemote(chatId: String): Outcome<Unit> = resolve(chatId, false)
    private suspend fun resolve(chatId: String, keepLocal: Boolean): Outcome<Unit> = withContext(Dispatchers.IO) {
        outcome { requests.withLock { refreshLocked(chatId); local.resolve(chatId, keepLocal) }; onPendingCommitted() }
    }

    /** 无本机源的跨端 READY 附件必须在线复核；原始上传设备仍可离线保留到可靠 outbox。 */
    suspend fun validateForSend(chatId: String, expectedLocalRevision: Long): Outcome<Unit> = withContext(Dispatchers.IO) {
        outcome {
            requests.withLock {
                val before = checkNotNull(cache.chatDrafts.get(chatId)) { "草稿尚未保存" }
                check(before.revision == expectedLocalRevision) { "草稿已变化，请重新发送" }
                check(local.state(chatId).failure == null) { "草稿同步有待处理的问题，请先选择保留的草稿" }
                check(!local.state(chatId).conflict) { "其他设备已修改草稿，请先选择保留哪一份" }
                val remoteAssets = before.assets.filter { cache.chatAssetUploads.upload(it.assetId)?.asset != it }
                if (remoteAssets.isNotEmpty()) {
                    val remote = refreshLocked(chatId)
                    check(remote.assetsAvailable && remoteAssets.all { it in remote.content?.assets.orEmpty() }) {
                        "其他设备草稿的附件已不可用，请重新选择文件"
                    }
                    val after = cache.chatDrafts.get(chatId)
                    check(after?.revision == expectedLocalRevision && after.assets == before.assets && !local.state(chatId).conflict) {
                        "草稿在发送前已变化，请核对后重新发送"
                    }
                }
            }
        }
    }

    internal suspend fun retryPending(): Outcome<Unit> = withContext(Dispatchers.IO) {
        retryIndependentPendingFamilies(
            { retryPendingMirrors(local.refreshTargets()) { chatId -> outcome { requests.withLock { refreshLocked(chatId) }; Unit } } },
            { retryPendingMirrors(local.workChats()) { chatId -> recoverChat(chatId) } },
        )
    }
    private suspend fun recoverChat(chatId: String): Outcome<Unit> = outcome {
        requests.withLock {
            // own ACK 后最多推进一个后继，更多输入交给合并唤醒，避免某一会话独占 worker。
            repeat(2) {
                val pending = local.nextCommand(chatId, System.currentTimeMillis()) ?: return@withLock
                val result = try { rpc.mutate(pending.command) } catch (failure: Exception) {
                    val code = status(failure)
                    if (code == 403 || (failure.isDefinitiveReliableCommandRejection() &&
                            !(code == 409 && pending.command.consumedClientMsgId != null))) {
                        local.fail(pending, when (code) {
                            403, 404 -> "此会话或附件已不可访问，本机草稿仍保留"
                            410 -> "草稿同步已超过可靠重试期限，请核对其他设备后选择保留的草稿"
                            else -> "草稿同步未获接受，请检查后选择保留的草稿"
                        })
                    }
                    if (code == 409 && pending.command.consumedClientMsgId != null) {
                        throw AppError.Business(503, "消息成功事实尚在收敛，将重试草稿清空")
                    }
                    throw failure
                }
                local.acknowledge(pending, result)
            }
            onPendingCommitted()
        }
    }
    internal fun nextExpiryAt(): Long? = local.nextExpiryAt()
    private fun status(failure: Throwable): Int? = when (failure) {
        is RpcStatusException -> failure.status
        is AppError.Business -> failure.code
        else -> null
    }
}
