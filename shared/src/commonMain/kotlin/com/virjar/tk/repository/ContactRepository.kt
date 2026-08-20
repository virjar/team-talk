package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.outcome
import com.virjar.tk.rpc.gen.ContactRpcProxy

class ContactRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = ContactRpcProxy(rpcClient)

    suspend fun listFriends(): Outcome<List<Contact>> {
        val projectionGeneration = localCache.contactProjectionGeneration()
        return outcome {
            rpc.list().also { list ->
                localCache.applyContactSnapshot(projectionGeneration, list)
            }
        }
    }

    suspend fun apply(toUid: String, remark: String? = null): Outcome<ContactApply> = outcome { rpc.apply(toUid, remark) }
    suspend fun accept(token: String): Outcome<ContactApply> = outcome { rpc.accept(token) }
    suspend fun reject(token: String): Outcome<ContactApply> = outcome { rpc.reject(token) }

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
