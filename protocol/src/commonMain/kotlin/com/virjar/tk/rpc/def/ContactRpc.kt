package com.virjar.tk.rpc.def

import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
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
}
