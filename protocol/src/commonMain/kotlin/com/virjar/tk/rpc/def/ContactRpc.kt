package com.virjar.tk.rpc.def

import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyLookup
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/** 联系人服务 RPC IDL；每个方法显式声明稳定 methodId。 */
@RpcService("contact")
interface ContactRpc {
    @RpcMethod(1)
    suspend fun list(): List<Contact>
    @RpcMethod(2)
    suspend fun apply(targetUid: String, remark: String?): ContactApply
    @RpcMethod(3)
    suspend fun accept(token: String): ContactApply
    @RpcMethod(4)
    suspend fun reject(token: String): ContactApply
    @RpcMethod(5)
    suspend fun delete(friendUid: String)
    @RpcMethod(6)
    suspend fun setRemark(friendUid: String, remark: String?)
    @RpcMethod(7)
    suspend fun blacklist(targetUid: String)
    @RpcMethod(8)
    suspend fun removeFromBlacklist(targetUid: String)
    /** 收到且仍待处理的申请，用于红点和快速入口。 */
    @RpcMethod(9)
    suspend fun listPendingApplies(): List<ContactApply>
    @RpcMethod(10)
    suspend fun listBlacklist(): List<Contact>
    /** 双向、含历史状态的游标分页记录。 */
    @RpcMethod(11)
    suspend fun listApplyRecords(beforeId: Long, limit: Int): List<ContactApplyRecord>
    /** 查询两人之间最新的待处理申请；不存在时返回 record=null。 */
    @RpcMethod(12)
    suspend fun getPendingApply(targetUid: String): ContactApplyLookup
}
