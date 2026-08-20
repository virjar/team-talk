package com.virjar.tk.rpc.def

import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyLookup
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.rpc.RpcService

/** 联系人服务 RPC IDL。⚠️ methodId 稳定：新方法只追加末尾。 */
@RpcService("contact")
interface ContactRpc {
    suspend fun list(): List<Contact>
    suspend fun apply(targetUid: String, remark: String?): ContactApply
    suspend fun accept(token: String): ContactApply
    suspend fun reject(token: String): ContactApply
    suspend fun delete(friendUid: String)
    suspend fun setRemark(friendUid: String, remark: String?)
    suspend fun blacklist(targetUid: String)
    suspend fun removeFromBlacklist(targetUid: String)
    suspend fun listApplies(): List<ContactApply>
    suspend fun listBlacklist(): List<Contact>
    /** 双向、含历史状态的游标分页记录；methodId 9 继续只返回收到的待处理申请。 */
    suspend fun listApplyRecords(beforeId: Long, limit: Int): List<ContactApplyRecord>
    /** 查询两人之间最新的待处理申请；不存在时返回 record=null。 */
    suspend fun getPendingApply(targetUid: String): ContactApplyLookup
}
