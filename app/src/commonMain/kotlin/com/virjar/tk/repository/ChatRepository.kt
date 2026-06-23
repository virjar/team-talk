package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.client.ensureSuccess
import com.virjar.tk.model.Chat
import com.virjar.tk.model.InviteLink
import com.virjar.tk.model.Member
import com.virjar.tk.outcome
import com.virjar.tk.protocol.ChatMethod
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ServiceId

class ChatRepository(
    private val rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    suspend fun createPersonalChat(targetUid: String): Outcome<Chat> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(targetUid) }
        val response = rpcClient.invoke(ServiceId.CHAT, ChatMethod.CREATE_PERSONAL.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: error("createPersonalChat: empty payload")
        ProtoCodec.decode(Chat, data)
    }

    suspend fun createGroup(name: String, avatar: String? = null, memberUids: List<String>): Outcome<Chat> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(name)
            writeString(avatar)
            writeVarInt(memberUids.size)
            for (uid in memberUids) writeString(uid)
        }
        val response = rpcClient.invoke(ServiceId.CHAT, ChatMethod.CREATE_GROUP.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: error("createGroup: empty payload")
        ProtoCodec.decode(Chat, data)
    }

    /** 拉取 chat 信息。失败时调用方可 `.recover { localCache.getChat(chatId) }` 降级。 */
    suspend fun getChat(chatId: String): Outcome<Chat?> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId) }
        val response = rpcClient.invoke(ServiceId.CHAT, ChatMethod.GET.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome null
        ProtoCodec.decode(Chat, data)
    }

    /** 拉取群成员。失败时调用方可 `.recover { localCache.getMembers(chatId) }` 降级。 */
    suspend fun getMembers(chatId: String): Outcome<List<Member>> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId) }
        val response = rpcClient.invoke(ServiceId.CHAT, ChatMethod.GET_MEMBERS.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        ProtoCodec.decodeList(Member, data)
    }

    suspend fun deleteChat(chatId: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId) }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.DELETE.id, payload).ensureSuccess()
        localCache.deleteChat(chatId)
    }

    suspend fun addMembers(chatId: String, uids: List<String>): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeVarInt(uids.size)
            for (uid in uids) writeString(uid)
        }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.ADD_MEMBERS.id, payload).ensureSuccess()
    }

    suspend fun createInviteLink(chatId: String, name: String = "", maxUses: Int = 0, expiresAt: Long = 0): Outcome<String> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeString(name)
            writeVarInt(maxUses)
            writeVarLong(expiresAt)
        }
        val response = rpcClient.invoke(ServiceId.CHAT, ChatMethod.CREATE_INVITE_LINK.id, payload)
        response.ensureSuccess()
        response.payload?.decodeToString() ?: error("createInviteLink: empty payload")
    }

    suspend fun listInviteLinks(chatId: String): Outcome<List<InviteLink>> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(chatId) }
        val response = rpcClient.invoke(ServiceId.CHAT, ChatMethod.LIST_INVITE_LINKS.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        ProtoCodec.decodeList(InviteLink, data)
    }

    suspend fun revokeInviteLink(token: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(token) }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.REVOKE_INVITE_LINK.id, payload).ensureSuccess()
    }

    suspend fun updateGroup(chatId: String, name: String? = null, avatar: String? = null, notice: String? = null): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeString(name)
            writeString(avatar)
            writeString(notice)
        }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.UPDATE.id, payload).ensureSuccess()
    }

    /** 移出群成员（踢人）。成功时同步移除本地缓存。 */
    suspend fun removeMember(chatId: String, memberUid: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeString(memberUid)
        }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.REMOVE_MEMBERS.id, payload).ensureSuccess()
        localCache.removeMember(chatId, memberUid)
    }

    /** 禁言成员，durationSeconds 秒。 */
    suspend fun muteMember(chatId: String, memberUid: String, durationSeconds: Int): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeString(memberUid)
            writeVarInt(durationSeconds)
        }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.MUTE_MEMBER.id, payload).ensureSuccess()
    }

    /** 解除成员禁言。 */
    suspend fun unmuteMember(chatId: String, memberUid: String): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeString(memberUid)
        }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.UNMUTE_MEMBER.id, payload).ensureSuccess()
    }

    /** 设置成员角色（2=群主, 1=管理员, 0=普通成员）。 */
    suspend fun setMemberRole(chatId: String, memberUid: String, role: Int): Outcome<Unit> = outcome {
        val payload = ProtoCodec.encodePayload {
            writeString(chatId)
            writeString(memberUid)
            writeVarInt(role)
        }
        rpcClient.invoke(ServiceId.CHAT, ChatMethod.SET_ROLE.id, payload).ensureSuccess()
    }
}
