package com.virjar.tk.server.application

import com.virjar.tk.server.domain.bot.BotAccountIdentity
import com.virjar.tk.server.domain.bot.BotAccountProvisioner
import com.virjar.tk.server.domain.bot.BotGroupMembership
import com.virjar.tk.server.domain.bot.BotMessageSender
import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.chat.GroupMemberAddition
import com.virjar.tk.server.domain.chat.GroupMemberAdditionFacts
import com.virjar.tk.server.domain.chat.GroupMemberRemoval
import com.virjar.tk.server.domain.chat.GroupMemberRemovalFacts
import com.virjar.tk.server.domain.chat.LockedChat
import com.virjar.tk.server.domain.chat.ServiceMemberProjectionCleanup
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.user.UserService
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Member

class UserServiceBotAccounts(
    private val users: UserService,
) : BotAccountProvisioner {
    override fun createServiceAccount(transaction: PgWriteTransactionContext, name: String): BotAccountIdentity =
        users.createServiceAccount(transaction, name).let { BotAccountIdentity(it.uid, it.name) }
}

class ChatServiceBotMembership(
    private val chats: ChatService,
) : BotGroupMembership {
    override fun activeChatIds(uid: String): Set<String> = chats.activeChatIdsForServiceMember(uid)

    override fun activeChatIds(transaction: PgReadTransactionContext, uid: String): Set<String> =
        chats.activeChatIdsForServiceMember(transaction, uid)

    override fun projectedChatIds(uid: String): Set<String> = chats.projectedChatIdsForServiceMember(uid)

    override fun projectedChatIds(transaction: PgReadTransactionContext, uid: String): Set<String> =
        chats.projectedChatIdsForServiceMember(transaction, uid)

    override fun lockChats(
        transaction: PgWriteTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = chats.lockChatsForServiceMember(transaction, chatIds, requireActive)

    override fun getActiveMember(
        transaction: PgReadTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = chats.getActiveMemberForService(transaction, chatId, uid)

    override fun addServiceMember(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = chats.addServiceMember(transaction, chatId, operatorUid, uid, authorize)

    override fun removeServiceMemberIfPresent(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        requireActiveChat: Boolean,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = chats.removeServiceMemberIfPresent(
        transaction,
        chatId,
        operatorUid,
        uid,
        requireActiveChat,
        authorize,
    )

    override fun cleanupServiceMemberProjection(
        transaction: PgWriteTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = chats.cleanupServiceMemberProjection(
        transaction,
        chatId,
        uid,
        lockedChat,
    )

    override fun invalidateCommittedMembershipChange(chatId: String) {
        chats.invalidateCommittedServiceMembershipChange(chatId)
    }
}

class MessageServiceBotSender(
    private val messages: MessageService,
) : BotMessageSender {
    override suspend fun send(
        senderUid: String,
        message: Message,
        authorizeAfterChatLock: (PgWriteTransactionContext) -> Unit,
    ): Long = messages.sendMessage(senderUid, message, authorizeAfterChatLock)
}
