package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.chat.toModel
import com.virjar.tk.model.Chat
import com.virjar.tk.protocol.*

class ChatRouteHandler(private val chatService: ChatService) {
    suspend fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (ChatMethod.fromId(methodId)) {
            ChatMethod.CREATE_PERSONAL -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encode(chatService.createPersonalChat(uid, readString()!!))
            }
            ChatMethod.CREATE_GROUP -> ProtoCodec.withPayload(payload!!) {
                val name = readString()!!
                val avatar = readString()
                val count = readVarInt()
                val uids = (0 until count).map { readString()!! }
                ProtoCodec.encode(chatService.createGroup(name, avatar, uid, uids))
            }
            ChatMethod.GET -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encode(chatService.getChat(readString()!!) ?: throw IllegalArgumentException("聊天不存在"))
            }
            ChatMethod.UPDATE -> ProtoCodec.withPayload(payload!!) {
                val chatId = readString()!!
                val name = readString()
                val avatar = readString()
                val notice = readString()
                chatService.updateGroup(uid, chatId, name, avatar, notice); ByteArray(0)
            }
            ChatMethod.DELETE -> ProtoCodec.withPayload(payload!!) {
                chatService.deleteChat(uid, readString()!!); ByteArray(0)
            }
            ChatMethod.ADD_MEMBERS -> ProtoCodec.withPayload(payload!!) {
                val chatId = readString()!!
                val count = readVarInt()
                val uids = (0 until count).map { readString()!! }
                chatService.addMembers(uid, chatId, uids); ByteArray(0)
            }
            ChatMethod.REMOVE_MEMBERS -> ProtoCodec.withPayload(payload!!) {
                chatService.removeMember(uid, readString()!!, readString()!!); ByteArray(0)
            }
            ChatMethod.GET_MEMBERS -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encodeList(chatService.getMembers(readString()!!))
            }
            ChatMethod.TRANSFER_OWNER -> ProtoCodec.withPayload(payload!!) {
                chatService.transferOwner(uid, readString()!!, readString()!!); ByteArray(0)
            }
            ChatMethod.SET_ROLE -> ProtoCodec.withPayload(payload!!) {
                chatService.setRole(uid, readString()!!, readString()!!, readVarInt()); ByteArray(0)
            }
            ChatMethod.MUTE_MEMBER -> ProtoCodec.withPayload(payload!!) {
                chatService.muteMember(uid, readString()!!, readString()!!, readVarInt()); ByteArray(0)
            }
            ChatMethod.UNMUTE_MEMBER -> ProtoCodec.withPayload(payload!!) {
                chatService.unmuteMember(uid, readString()!!, readString()!!); ByteArray(0)
            }
            ChatMethod.MUTE_ALL -> ProtoCodec.withPayload(payload!!) {
                chatService.muteAll(uid, readString()!!); ByteArray(0)
            }
            ChatMethod.UNMUTE_ALL -> ProtoCodec.withPayload(payload!!) {
                chatService.unmuteAll(uid, readString()!!); ByteArray(0)
            }
            ChatMethod.CREATE_INVITE_LINK -> ProtoCodec.withPayload(payload!!) {
                val chatId = readString()!!
                val name = readString() ?: ""
                val maxUses = readVarInt()
                val expiresAt = readVarLong()
                chatService.createInviteLink(uid, chatId, name, maxUses, expiresAt).encodeToByteArray()
            }
            ChatMethod.LIST_INVITE_LINKS -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encodeList(chatService.listInviteLinks(uid, readString()!!).map { it.toModel() })
            }
            ChatMethod.REVOKE_INVITE_LINK -> ProtoCodec.withPayload(payload!!) {
                chatService.revokeInviteLink(uid, readString()!!); ByteArray(0)
            }
            ChatMethod.JOIN_BY_INVITE -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encode(chatService.joinByInvite(uid, readString()!!))
            }
            ChatMethod.GET_INVITE_INFO -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encode(chatService.getInviteInfo(readString()!!).toModel())
            }
        }
    }
}
