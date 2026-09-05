package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingContactDecision
import com.virjar.tk.shared.client.PendingContactDecisionType
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.gen.ContactRpcProxy
import java.util.UUID

/** 脱敏的 ACK 后提示，用于在后台精确回放之后收敛 UI 投影。 */
data class RecoveredContactDecision(
    val peerUid: String,
    val decision: PendingContactDecisionType,
)

class ContactRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
    private val newDecisionOperationId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onPendingReliableCommandCommitted: () -> Unit = {},
    private val onPendingContactDecisionRecovered: (RecoveredContactDecision) -> Unit = {},
) {
    private val rpc = ContactRpcProxy(rpcClient)

    suspend fun listFriends(): Outcome<List<Contact>> {
        val projectionGeneration = localCache.contactProjectionGeneration()
        return outcome {
            val remote = rpc.list()
            localCache.applyContactSnapshot(projectionGeneration, remote)
            // 联系人通知可能与这笔请求竞争。缓存按好友对过期快照做合并或拒绝，
            // 因此调用方必须收到同一份收敛后的事实，而不是投影门禁
            // 刻意拒绝掉的原始响应。
            localCache.getContacts()
        }
    }

    suspend fun apply(toUid: String, remark: String? = null): Outcome<ContactApply> = outcome { rpc.apply(toUid, remark) }
    suspend fun accept(token: String): Outcome<ContactApply> =
        decide(token, PendingContactDecisionType.ACCEPT)

    suspend fun reject(token: String): Outcome<ContactApply> =
        decide(token, PendingContactDecisionType.REJECT)

    private suspend fun decide(
        token: String,
        decision: PendingContactDecisionType,
    ): Outcome<ContactApply> = outcome {
        val pending = localCache.preparePendingContactDecision(
            PendingContactDecision(
                operationId = newDecisionOperationId(),
                token = token,
                decision = decision,
                createdAt = nowMillis(),
            ),
        )
        onPendingReliableCommandCommitted()
        sendPendingDecision(pending)
    }

    private suspend fun sendPendingDecision(pending: PendingContactDecision): ContactApply {
        val result = try {
            when (pending.decision) {
                PendingContactDecisionType.ACCEPT ->
                    rpc.accept(pending.operationId, pending.createdAt, pending.token)
                PendingContactDecisionType.REJECT ->
                    rpc.reject(pending.operationId, pending.createdAt, pending.token)
            }
        } catch (failure: Exception) {
            if (failure.isDefinitiveReliableCommandRejection()) {
                localCache.clearPendingContactDecision(pending.operationId)
            }
            throw failure
        }
        // 有条件的清除可以防止较早的响应使另一个本地代次退场。
        val acknowledged = localCache.clearPendingContactDecision(pending.operationId)
        if (acknowledged) {
            // 同步且不含秘密的发布封住了 ACK → 持久清除 → UI 交接
            // 之间的取消窗口。前台与恢复竞争只有一个有条件的赢家。
            onPendingContactDecisionRecovered(
                RecoveredContactDecision(
                    peerUid = result.fromUid,
                    decision = pending.decision,
                ),
            )
        }
        return result
    }

    internal suspend fun retryPendingDecisions(): Outcome<Unit> = retryPendingMirrors(
        snapshot = localCache.getPendingContactDecisions(),
    ) { pending ->
        outcome {
            sendPendingDecision(pending)
        }
    }

    fun discardPendingDecision(operationId: String): Boolean =
        localCache.clearPendingContactDecision(operationId)

    suspend fun deleteFriend(friendUid: String): Outcome<Unit> = outcome {
        rpc.delete(friendUid)
        localCache.deleteContact(friendUid)
    }

    suspend fun setRemark(friendUid: String, remark: String?): Outcome<Unit> = outcome { rpc.setRemark(friendUid, remark) }
    suspend fun blacklist(targetUid: String): Outcome<Unit> = outcome { rpc.blacklist(targetUid) }
    suspend fun removeFromBlacklist(targetUid: String): Outcome<Unit> = outcome { rpc.removeFromBlacklist(targetUid) }
    suspend fun listBlacklist(): Outcome<List<Contact>> = outcome { rpc.listBlacklist() }
    /** 仅收到且待处理，用于红点与快速入口。 */
    suspend fun listPendingApplies(): Outcome<List<ContactApply>> = outcome { rpc.listPendingApplies() }

    /** 双向申请历史，按 id 倒序游标分页。 */
    suspend fun listApplyRecords(
        beforeId: Long = 0,
        limit: Int = DEFAULT_APPLY_RECORD_PAGE_SIZE,
    ): Outcome<List<ContactApplyRecord>> = outcome { rpc.listApplyRecords(beforeId, limit) }

    /** 精确查询当前用户与目标用户之间的待处理申请；不能用收件箱首屏推断资料页状态。 */
    suspend fun getPendingApply(targetUid: String): Outcome<ContactApplyRecord?> = outcome {
        rpc.getPendingApply(targetUid).record
    }

    companion object {
        const val DEFAULT_APPLY_RECORD_PAGE_SIZE = 50
    }
}
