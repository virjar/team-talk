package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.model.Chat
import com.virjar.tk.model.InviteLink
import com.virjar.tk.model.Member
import com.virjar.tk.outcome
import com.virjar.tk.rpc.gen.ChatRpcProxy

class ChatRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = ChatRpcProxy(rpcClient)

    suspend fun createPersonalChat(targetUid: String): Outcome<Chat> = outcome { rpc.createPersonal(targetUid) }

    suspend fun createGroup(name: String, avatar: String? = null, memberUids: List<String>): Outcome<Chat> = outcome {
        rpc.createGroup(name, avatar, memberUids)
    }

    suspend fun getChat(chatId: String): Outcome<Chat?> = outcome { rpc.get(chatId) }
    suspend fun getMembers(chatId: String): Outcome<List<Member>> = outcome { rpc.getMembers(chatId) }

    suspend fun deleteChat(chatId: String): Outcome<Unit> = outcome {
        rpc.delete(chatId)
        localCache.deleteChat(chatId)
    }

    suspend fun addMembers(chatId: String, uids: List<String>): Outcome<Unit> = outcome { rpc.addMembers(chatId, uids) }

    suspend fun createInviteLink(chatId: String, name: String = "", maxUses: Int = 0, expiresAt: Long = 0): Outcome<String> = outcome {
        rpc.createInviteLink(chatId, name, maxUses, expiresAt)
    }

    suspend fun listInviteLinks(chatId: String): Outcome<List<InviteLink>> = outcome { rpc.listInviteLinks(chatId) }
    suspend fun revokeInviteLink(token: String): Outcome<Unit> = outcome { rpc.revokeInviteLink(token) }

    suspend fun updateGroup(chatId: String, name: String? = null, avatar: String? = null, notice: String? = null): Outcome<Unit> = outcome {
        rpc.update(chatId, name, avatar, notice)
    }

    suspend fun removeMember(chatId: String, memberUid: String): Outcome<Unit> = outcome {
        rpc.removeMembers(chatId, memberUid)
        localCache.removeMember(chatId, memberUid)
    }

    suspend fun muteMember(chatId: String, memberUid: String, durationSeconds: Int): Outcome<Unit> = outcome { rpc.muteMember(chatId, memberUid, durationSeconds) }
    suspend fun unmuteMember(chatId: String, memberUid: String): Outcome<Unit> = outcome { rpc.unmuteMember(chatId, memberUid) }
    suspend fun setMemberRole(chatId: String, memberUid: String, role: Int): Outcome<Unit> = outcome { rpc.setRole(chatId, memberUid, role) }
}
