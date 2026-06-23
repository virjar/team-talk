package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.protocol.*

class ConversationRouteHandler(private val conversationService: ConversationService) {
    suspend fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (ConversationMethod.fromId(methodId)) {
            ConversationMethod.LIST -> ProtoCodec.encodeList(conversationService.listConversations(uid))
            ConversationMethod.SYNC -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encodeList(conversationService.syncConversations(uid, readVarLong()))
            }
            ConversationMethod.SET_DRAFT -> ProtoCodec.withPayload(payload!!) {
                conversationService.setDraft(uid, readString()!!, readString()); ByteArray(0)
            }
            ConversationMethod.SET_PIN -> ProtoCodec.withPayload(payload!!) {
                // 客户端用 writeVarInt 写布尔值(0/1)，服务端必须用 readVarInt 对齐（readByte 仅在值≤127时巧合兼容）
                conversationService.setPin(uid, readString()!!, readVarInt() != 0); ByteArray(0)
            }
            ConversationMethod.SET_MUTE -> ProtoCodec.withPayload(payload!!) {
                conversationService.setMute(uid, readString()!!, readVarInt() != 0); ByteArray(0)
            }
            ConversationMethod.DELETE -> ProtoCodec.withPayload(payload!!) {
                conversationService.deleteConversation(uid, readString()!!); ByteArray(0)
            }
        }
    }
}
