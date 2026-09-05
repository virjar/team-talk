package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.InviteLink
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 群组服务 RPC IDL；每个方法显式声明当前协议基线的 methodId。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("chat")
interface ChatRpc {
    @RpcMethod(1)
    suspend fun createPersonal(targetUid: String): Chat
    /** [operationId] 是一个 canonical UUID，该创建命令的每次重试都复用同一值。 */
    @RpcMethod(2)
    suspend fun createGroup(operationId: String, name: String, avatar: String?, memberUids: List<String>): Chat
    @RpcMethod(3)
    suspend fun get(chatId: String): Chat
    @RpcMethod(4)
    suspend fun update(chatId: String, name: String?, avatar: String?, notice: String?)
    @RpcMethod(5)
    suspend fun delete(chatId: String)
    @RpcMethod(6)
    suspend fun addMembers(chatId: String, uids: List<String>)
    @RpcMethod(7)
    suspend fun removeMembers(chatId: String, targetUid: String)
    @RpcMethod(8)
    suspend fun getMembers(chatId: String): List<Member>
    @RpcMethod(9)
    suspend fun transferOwner(chatId: String, newOwnerUid: String)
    @RpcMethod(10)
    suspend fun setRole(chatId: String, targetUid: String, role: Int)
    @RpcMethod(11)
    suspend fun muteMember(chatId: String, targetUid: String, durationSeconds: Int)
    @RpcMethod(12)
    suspend fun unmuteMember(chatId: String, targetUid: String)
    @RpcMethod(13)
    suspend fun muteAll(chatId: String)
    @RpcMethod(14)
    suspend fun unmuteAll(chatId: String)
    /** [operationId]/[issuedAt] 在本地冻结并复用，直到本次创建被确认。 */
    @RpcMethod(15)
    suspend fun createInviteLink(
        operationId: String,
        issuedAt: Long,
        chatId: String,
        name: String,
        maxUses: Int,
        expiresAt: Long,
    ): String
    @RpcMethod(16)
    suspend fun listInviteLinks(chatId: String): List<InviteLink>
    @RpcMethod(17)
    suspend fun revokeInviteLink(token: String)
    @RpcMethod(18)
    suspend fun joinByInvite(token: String): Chat
    @RpcMethod(19)
    suspend fun getInviteInfo(token: String): InviteLink
    @RpcMethod(20)
    suspend fun leaveGroup(chatId: String)

    /** 幂等取回（必要时创建）当前用户的"保存的消息"私有会话。 */
    @RpcMethod(21)
    suspend fun getOrCreateSavedChat(): Chat
}
