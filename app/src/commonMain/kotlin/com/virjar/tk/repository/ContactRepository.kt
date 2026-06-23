package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.client.ensureSuccess
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.outcome
import com.virjar.tk.protocol.ContactMethod
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ServiceId

class ContactRepository(
    private val rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    /** 拉取好友列表。成功时写入 LocalCache。失败时调用方可 `.recover { localCache.getContacts() }` 降级。 */
    suspend fun listFriends(): Outcome<List<Contact>> = outcome {
        val response = rpcClient.invoke(ServiceId.CONTACT, ContactMethod.LIST.id)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        val contacts = ProtoCodec.decodeList(Contact, data)
        // 写入 LocalCache，让 ContactViewModel 的 observeContacts 能更新
        contacts.forEach { localCache.upsertContact(it) }
        contacts
    }

    suspend fun apply(toUid: String, remark: String? = null): Outcome<ContactApply> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(toUid); writeString(remark) }
        val response = rpcClient.invoke(ServiceId.CONTACT, ContactMethod.APPLY.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: error("apply: empty payload")
        ProtoCodec.decode(ContactApply, data)
    }

    suspend fun accept(token: String): Outcome<ContactApply> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(token) }
        val response = rpcClient.invoke(ServiceId.CONTACT, ContactMethod.ACCEPT.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: error("accept: empty payload")
        ProtoCodec.decode(ContactApply, data)
    }

    suspend fun reject(token: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(token) }
        rpcClient.invoke(ServiceId.CONTACT, ContactMethod.REJECT.id, payload).ensureSuccess()
    }

    suspend fun deleteFriend(friendUid: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(friendUid) }
        rpcClient.invoke(ServiceId.CONTACT, ContactMethod.DELETE.id, payload).ensureSuccess()
        localCache.deleteContact(friendUid)
    }

    suspend fun setRemark(friendUid: String, remark: String?): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(friendUid); writeString(remark) }
        rpcClient.invoke(ServiceId.CONTACT, ContactMethod.SET_REMARK.id, payload).ensureSuccess()
    }

    suspend fun blacklist(targetUid: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(targetUid) }
        rpcClient.invoke(ServiceId.CONTACT, ContactMethod.BLACKLIST.id, payload).ensureSuccess()
    }

    suspend fun removeFromBlacklist(targetUid: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(targetUid) }
        rpcClient.invoke(ServiceId.CONTACT, ContactMethod.BLACKLIST_REMOVE.id, payload).ensureSuccess()
    }

    suspend fun listBlacklist(): Outcome<List<Contact>> = outcome {
        val response = rpcClient.invoke(ServiceId.CONTACT, ContactMethod.LIST_BLACKLIST.id)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        ProtoCodec.decodeList(Contact, data)
    }

    suspend fun listApplies(): Outcome<List<ContactApply>> = outcome {
        val response = rpcClient.invoke(ServiceId.CONTACT, ContactMethod.LIST_APPLIES.id)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        ProtoCodec.decodeList(ContactApply, data)
    }
}
