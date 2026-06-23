package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.*

class MessageRouteHandler(
    private val messageService: MessageService,
    private val conversationService: ConversationService,
) {
    suspend fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (MessageMethod.fromId(methodId)) {
            MessageMethod.GET_HISTORY -> ProtoCodec.withPayload(payload!!) {
                val chatId = readString()!!
                val fromSeq = readVarLong()
                val limit = readVarInt()
                ProtoCodec.encodeList(messageService.getHistory(uid, chatId, fromSeq, limit))
            }
            MessageMethod.REVOKE -> ProtoCodec.withPayload(payload!!) {
                messageService.revokeMessage(uid, readString()!!, readVarLong()); ByteArray(0)
            }
            MessageMethod.EDIT -> {
                val msg = ProtoCodec.decode(Message, payload!!)
                messageService.editMessage(uid, msg.chatId, msg.serverSeq, msg)
                ByteArray(0)
            }
            MessageMethod.FORWARD -> ProtoCodec.withPayload(payload!!) {
                val srcChatId = readString()!!
                val srcSeq = readVarLong()
                val targetChatId = readString()!!
                ProtoCodec.encode(messageService.forwardMessage(uid, srcChatId, srcSeq, targetChatId))
            }
            MessageMethod.SEARCH -> ProtoCodec.withPayload(payload!!) {
                val chatId = readString()!!
                val keyword = readString()!!
                val limit = readVarInt()
                ProtoCodec.encodeList(messageService.searchMessages(uid, chatId, keyword, limit))
            }
            MessageMethod.MARK_READ -> ProtoCodec.withPayload(payload!!) {
                conversationService.markRead(uid, readString()!!, readVarLong()); ByteArray(0)
            }
        }
    }
}
