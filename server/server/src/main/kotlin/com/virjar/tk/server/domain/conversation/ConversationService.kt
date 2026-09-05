package com.virjar.tk.server.domain.conversation

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.chat.UnmanagedChatPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.ConversationPagePolicy
import com.virjar.tk.protocol.model.ConversationPageRequest
import com.virjar.tk.protocol.model.ConversationWirePolicy
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ReadSyncPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

class ConversationService(
    private val conversationRepo: ConversationRepository,
    private val lifecycleGate: ChatLifecycleGate,
    private val unitOfWork: PgUnitOfWork,
    private val managedChats: ManagedChatPolicy = UnmanagedChatPolicy,
) {

    suspend fun listConversationPage(
        uid: String,
        request: ConversationPageRequest,
    ): ConversationPage = withContext(Dispatchers.IO) {
        val page = conversationRepo.listConversationPage(
            uid = uid,
            afterChatId = ConversationPageCursorCodec.decode(request.cursor),
            pageSize = ConversationPage.MAX_PAGE_SIZE,
        )
        ConversationPage(
            items = page.items,
            nextCursor = page.nextChatId?.let(ConversationPageCursorCodec::encode),
        )
    }

    suspend fun setDraft(uid: String, chatId: String, draft: String?) = lifecycleGate.withChat(chatId) {
        // 草稿与最终消息共享 Markdown 资源预算。禁止截断：截断 fence/table 会制造
        // 无法预览的损坏源码；非法或超限草稿应明确拒绝，由本地缓存保留完整内容。
        val validatedDraft = draft?.let(MessageBodyPolicy::validateMarkdown)
        unitOfWork.write {
            requireProjectionReady(transaction, chatId)
            val conv = conversationRepo.setDraft(transaction, uid, chatId, validatedDraft)
            appendEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    suspend fun setPin(uid: String, chatId: String, pinned: Boolean) = lifecycleGate.withChat(chatId) {
        unitOfWork.write {
            requireProjectionReady(transaction, chatId)
            val conv = conversationRepo.setPin(transaction, uid, chatId, pinned)
            appendEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    suspend fun setMute(uid: String, chatId: String, muted: Boolean) = lifecycleGate.withChat(chatId) {
        unitOfWork.write {
            requireProjectionReady(transaction, chatId)
            val conv = conversationRepo.setMute(transaction, uid, chatId, muted)
            appendEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    suspend fun deleteConversation(uid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        unitOfWork.write {
            requireProjectionReady(transaction, chatId)
            if (conversationRepo.deleteConversation(transaction, uid, chatId)) {
                appendEvent(uid, NotifyType.CONVERSATION_DELETED, deletedConversation(chatId))
            }
        }
    }

    suspend fun markRead(uid: String, chatId: String, readSeq: Long) = lifecycleGate.withChat(chatId) {
        unitOfWork.write {
            requireProjectionReady(transaction, chatId)
            // maxSeq 校验、操作者水位、每个对端水位以及所有持久化事件共享一个已锁定的
            // 数据库快照。因此一个并发的较低游标无法获胜。
            val mutation = conversationRepo.markRead(transaction, uid, chatId, readSeq)
            val authoritativeReadSeq = mutation.conversation.readSeq
            if (mutation.actorChanged) {
                appendEvent(uid, NotifyType.CONVERSATION_UPDATED, mutation.conversation)
            }
            mutation.advancedPeerUids.forEach { memberUid ->
                appendEvent(
                    memberUid,
                    NotifyType.READ_SYNC,
                    ReadSyncPayload(uid, chatId, authoritativeReadSeq),
                )
            }
        }
    }

    private fun requireProjectionReady(transaction: PgWriteTransactionContext, chatId: String) {
        val authority = managedChats.lockAuthority(transaction, listOf(chatId)).getValue(chatId)
        require(authority.ready) { "受管群投影尚未收敛" }
    }

    private fun deletedConversation(chatId: String): Conversation {
        // 哨兵 Conversation：CONVERSATION_DELETED 通知客户端只用 chatId 定位删除目标，
        // chatType=0 是占位值（合法值为 1=PERSONAL/2=GROUP），客户端不应读取它。
        return Conversation(chatId = chatId, chatType = 0)
    }
}

/** 服务端持有的编解码器：客户端逐字节保留这个值，绝不检查其字段。 */
private object ConversationPageCursorCodec {
    private const val FORMAT_VERSION = 1
    private const val MAX_DECODED_BYTES = 96
    private const val INVALID_CURSOR = "会话分页游标无效"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(chatId: String): String {
        requireValidChatId(chatId)
        val raw = PacketBuffer().apply {
            writeByte(FORMAT_VERSION)
            writeString(chatId)
        }.toByteArray()
        check(raw.size <= MAX_DECODED_BYTES) { "Conversation cursor encoding exceeded its budget" }
        return encoder.encodeToString(raw).also(ConversationPagePolicy::requireOpaqueCursor)
    }

    fun decode(encoded: String?): String? {
        if (encoded == null) return null
        return try {
            ConversationPagePolicy.requireOpaqueCursor(encoded)
            val raw = decoder.decode(encoded)
            require(raw.size <= MAX_DECODED_BYTES) { INVALID_CURSOR }
            val buffer = PacketBuffer(raw)
            require(buffer.readByte() == FORMAT_VERSION) { INVALID_CURSOR }
            val chatId = buffer.readRequiredString(
                maxByteLength = ConversationWirePolicy.MAX_CHAT_ID_LENGTH,
                fieldName = "conversation cursor chatId",
            )
            buffer.requireExhausted("conversation cursor")
            require(encoder.encodeToString(raw) == encoded) { INVALID_CURSOR }
            requireValidChatId(chatId)
            chatId
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(INVALID_CURSOR)
        }
    }

    private fun requireValidChatId(chatId: String) {
        require(
                chatId.isNotEmpty() &&
                chatId.length <= ConversationWirePolicy.MAX_CHAT_ID_LENGTH &&
                chatId.all { it.code in 0x21..0x7e },
        ) { INVALID_CURSOR }
    }
}
