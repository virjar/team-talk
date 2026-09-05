package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.model.ContactApplyLookup
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 联系人服务 RPC IDL；每个方法显式声明当前协议基线的 methodId。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("contact")
interface ContactRpc {
    @RpcMethod(1)
    suspend fun list(): List<Contact>
    @RpcMethod(2)
    suspend fun apply(targetUid: String, remark: String?): ContactApply
    /** [operationId]/[issuedAt] 在本地冻结并复用，直到本决策被确认。 */
    @RpcMethod(3)
    suspend fun accept(operationId: String, issuedAt: Long, token: String): ContactApply
    /** [operationId]/[issuedAt] 在本地冻结并复用，直到本决策被确认。 */
    @RpcMethod(4)
    suspend fun reject(operationId: String, issuedAt: Long, token: String): ContactApply
    @RpcMethod(5)
    suspend fun delete(friendUid: String)
    @RpcMethod(6)
    suspend fun setRemark(friendUid: String, remark: String?)
    @RpcMethod(7)
    suspend fun blacklist(targetUid: String)
    @RpcMethod(8)
    suspend fun removeFromBlacklist(targetUid: String)
    /** 收到且仍待处理的完整有界视图，用于红点和快速入口。 */
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
    /** 当前认证用户的完整好友集合与同一 Registry revision 下的在线子集。 */
    @RpcMethod(13)
    suspend fun getPresenceSnapshot(): FriendPresenceSnapshot
}
