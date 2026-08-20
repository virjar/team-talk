package com.virjar.tk.application.bot

import com.virjar.tk.domain.bot.BotAccountIdentity
import com.virjar.tk.domain.bot.BotAccountProvisioner
import com.virjar.tk.domain.bot.BotGroupMembership
import com.virjar.tk.domain.bot.BotMessageSender
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.model.Message

class UserServiceBotAccounts(
    private val users: UserService,
) : BotAccountProvisioner {
    override fun createServiceAccount(name: String): BotAccountIdentity =
        users.createServiceAccount(name).let { BotAccountIdentity(it.uid, it.name) }
}

class ChatServiceBotMembership(
    private val chats: ChatService,
) : BotGroupMembership {
    override suspend fun addServiceMember(chatId: String, uid: String) {
        // BotService already owns the same ChatLifecycleGate around grant + membership.
        chats.adminAddServiceMemberWithinLifecycle(chatId, uid)
    }

    override suspend fun removeServiceMember(chatId: String, uid: String) {
        chats.adminRemoveServiceMemberWithinLifecycle(chatId, uid)
    }
}

class MessageServiceBotSender(
    private val messages: MessageService,
) : BotMessageSender {
    override suspend fun send(senderUid: String, message: Message): Long =
        messages.sendMessage(senderUid, message)
}
