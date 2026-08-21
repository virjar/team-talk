package com.virjar.tk.application.bot

import com.virjar.tk.domain.bot.BotAccountIdentity
import com.virjar.tk.domain.bot.BotAccountProvisioner
import com.virjar.tk.domain.bot.BotGroupMembership
import com.virjar.tk.domain.bot.BotMessageSender
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.chat.GroupMemberAddition
import com.virjar.tk.domain.chat.GroupMemberAdditionFacts
import com.virjar.tk.domain.chat.GroupMemberRemoval
import com.virjar.tk.domain.chat.GroupMemberRemovalFacts
import com.virjar.tk.domain.chat.LockedChat
import com.virjar.tk.domain.chat.ServiceMemberProjectionCleanup
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.model.Message
import com.virjar.tk.model.Member

class UserServiceBotAccounts(
    private val users: UserService,
) : BotAccountProvisioner {
    override fun createServiceAccount(transaction: PgTransactionContext, name: String): BotAccountIdentity =
        users.createServiceAccount(transaction, name).let { BotAccountIdentity(it.uid, it.name) }
}

class ChatServiceBotMembership(
    private val chats: ChatService,
) : BotGroupMembership {
    override fun activeChatIds(uid: String): Set<String> = chats.activeChatIdsForServiceMember(uid)

    override fun activeChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        chats.activeChatIdsForServiceMember(transaction, uid)

    override fun projectedChatIds(uid: String): Set<String> = chats.projectedChatIdsForServiceMember(uid)

    override fun projectedChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        chats.projectedChatIdsForServiceMember(transaction, uid)

    override fun lockChats(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = chats.lockChatsForServiceMember(transaction, chatIds, requireActive)

    override fun getActiveMember(
        transaction: PgTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = chats.getActiveMemberForService(transaction, chatId, uid)

    override fun addServiceMember(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = chats.addServiceMember(transaction, chatId, operatorUid, uid, authorize)

    override fun removeServiceMemberIfPresent(
        transaction: PgTransactionContext,
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
        transaction: PgTransactionContext,
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
    override suspend fun send(senderUid: String, message: Message): Long =
        messages.sendMessage(senderUid, message)
}
