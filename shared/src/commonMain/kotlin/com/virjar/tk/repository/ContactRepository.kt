package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.outcome
import com.virjar.tk.rpc.gen.ContactRpcProxy

class ContactRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = ContactRpcProxy(rpcClient)

    suspend fun listFriends(): Outcome<List<Contact>> = outcome {
        rpc.list().also { list -> list.forEach { localCache.upsertContact(it) } }
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
    suspend fun listApplies(): Outcome<List<ContactApply>> = outcome { rpc.listApplies() }
}
