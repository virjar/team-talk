package com.virjar.tk.rpc.def

import com.virjar.tk.model.Chat
import com.virjar.tk.model.InviteLink
import com.virjar.tk.model.Member
import com.virjar.tk.rpc.RpcService

/** 群组服务 RPC IDL。⚠️ methodId 稳定：新方法只追加末尾。 */
@RpcService("chat")
interface ChatRpc {
    suspend fun createPersonal(targetUid: String): Chat
    suspend fun createGroup(name: String, avatar: String?, memberUids: List<String>): Chat
    suspend fun get(chatId: String): Chat
    suspend fun update(chatId: String, name: String?, avatar: String?, notice: String?)
    suspend fun delete(chatId: String)
    suspend fun addMembers(chatId: String, uids: List<String>)
    suspend fun removeMembers(chatId: String, targetUid: String)
    suspend fun getMembers(chatId: String): List<Member>
    suspend fun transferOwner(chatId: String, newOwnerUid: String)
    suspend fun setRole(chatId: String, targetUid: String, role: Int)
    suspend fun muteMember(chatId: String, targetUid: String, durationSeconds: Int)
    suspend fun unmuteMember(chatId: String, targetUid: String)
    suspend fun muteAll(chatId: String)
    suspend fun unmuteAll(chatId: String)
    suspend fun createInviteLink(chatId: String, name: String, maxUses: Int, expiresAt: Long): String
    suspend fun listInviteLinks(chatId: String): List<InviteLink>
    suspend fun revokeInviteLink(token: String)
    suspend fun joinByInvite(token: String): Chat
    suspend fun getInviteInfo(token: String): InviteLink
}
