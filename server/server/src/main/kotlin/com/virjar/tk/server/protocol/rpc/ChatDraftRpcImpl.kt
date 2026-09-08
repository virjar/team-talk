package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.model.ChatDraftCommand
import com.virjar.tk.protocol.rpc.gen.ChatDraftRpcStub
import com.virjar.tk.server.domain.conversation.ChatDraftService

class ChatDraftRpcImpl(uid: String, private val service: ChatDraftService) : ChatDraftRpcStub(uid) {
    override suspend fun get(chatId: String) = service.get(uid, chatId)
    override suspend fun mutate(command: ChatDraftCommand) = service.mutate(uid, command)
}
