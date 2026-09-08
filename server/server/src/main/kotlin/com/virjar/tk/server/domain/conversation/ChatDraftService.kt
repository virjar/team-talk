package com.virjar.tk.server.domain.conversation

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.model.*
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.attachment.AttachmentService
import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.chat.UnmanagedChatPolicy
import com.virjar.tk.server.domain.command.*
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope

/** An account owns one versioned ready draft per chat. The legacy string is a compatibility projection. */
class ChatDraftService(
    private val repository: ChatDraftRepository,
    private val conversations: ConversationRepository,
    private val unitOfWork: PgUnitOfWork,
    private val lifecycle: ChatLifecycleGate,
    private val attachmentLifecycle: AttachmentLifecycleGate,
    private val attachments: AttachmentService,
    private val catalog: AttachmentCatalog,
    private val messages: ChatDraftMessageLookup,
    private val clock: () -> Long = System::currentTimeMillis,
    private val managedChats: ManagedChatPolicy = UnmanagedChatPolicy,
) {
    suspend fun get(uid: String, chatId: String): ChatDraftSnapshot {
        requireChatDraftChatId(chatId)
        return unitOfWork.read {
            repository.requireAccess(transaction, uid, chatId)
            available(repository.get(transaction, uid, chatId))
        }
    }

    suspend fun mutate(uid: String, command: ChatDraftCommand): ChatDraftMutationResult = lifecycle.withChat(command.chatId) {
        val fingerprint = reliableCommandFingerprint(uid, java.util.Base64.getEncoder().encodeToString(ProtoCodec.encode(command)))
        // Exact retries and CAS conflicts never need a vanished old attachment to be available again.
        val completed = unitOfWork.write { existingResult(uid, command, fingerprint) }
        if (completed != null) return@withChat completed
        val paths = command.content?.assets.orEmpty().flatMap { it.attachments() }.map { it.path }
        val mutation: suspend () -> ChatDraftMutationResult = {
            val content = command.content?.let { it.copy(assets = attachments.resolveAssets(it.assets, uid)) }
            unitOfWork.write {
                existingResult(uid, command, fingerprint) ?: run {
                    val before = repository.get(transaction, uid, command.chatId)
                    val knownPaths = before.content?.assets.orEmpty().flatMap { it.attachments() }.mapTo(hashSetOf()) { it.path }
                    content?.assets.orEmpty().flatMap { it.attachments() }.forEach { asset ->
                        require(asset.path in knownPaths || (catalog.getOwnerUid(asset.path) == uid && catalog.isStaging(asset.path))) {
                            "新草稿资产必须是本人上传的暂存文件，不能将其他业务对象的附件转成私有草稿"
                        }
                    }
                    content?.replyToClientMsgId?.let { id ->
                        val reply = messages.find(command.chatId, id)
                        require(reply != null && reply.serverSeq == content.replyToServerSeq) { "回复目标不存在或身份不匹配" }
                    }
                    command.consumedClientMsgId?.let { id ->
                        val sent = messages.find(command.chatId, id)
                        if (sent == null || sent.senderUid != uid || sent.serverSeq <= 0) {
                            throw ReliableCommandConflictException("消息尚未确认成功，草稿和附件已保留，请稍后重试")
                        }
                    }
                    val after = ChatDraftSnapshot(command.chatId, before.revision + 1,
                        maxOf(clock(), before.updatedAt), content)
                    repository.save(transaction, uid, after)
                    // Old clients get only ordinary Markdown. Their later setDraft cannot alter this independent row.
                    val mirror = content?.takeIf { it.assets.isEmpty() }?.markdown?.takeIf { it.isNotEmpty() }
                    val member = try { repository.requireAccess(transaction, uid, command.chatId); true }
                        catch (_: ChatAccessDeniedException) { false }
                    if (member) {
                        val conversation = conversations.setDraft(transaction, uid, command.chatId, mirror)
                        appendEvent(uid, NotifyType.CONVERSATION_UPDATED, conversation)
                    }
                    appendEvent(uid, NotifyType.CHAT_DRAFT_CHANGED, ChatDraftChangedPayload(command.chatId, after.revision))
                    repository.record(transaction, uid, command.operationId, command.issuedAt, ChatDraftReceipt(fingerprint, true, after.revision))
                    ChatDraftMutationResult(true, after.revision, after)
                }
            }
        }
        if (paths.isEmpty()) mutation() else attachmentLifecycle.withReferenceMutation(paths, mutation)
    }

    /** Runs under the per-user capacity row, so operation identities and aggregate limits cannot race across chats. */
    private fun PgWriteScope.existingResult(uid: String, command: ChatDraftCommand, fingerprint: String): ChatDraftMutationResult? {
        ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, clock(), "草稿同步")
        if (command.content != null) {
            require(managedChats.lockAuthority(transaction, listOf(command.chatId)).getValue(command.chatId).ready) { "受管群投影尚未收敛" }
        }
        repository.lock(transaction, uid, command.chatId, ownerClear = command.content == null)
        val current = available(repository.get(transaction, uid, command.chatId))
        repository.receipt(transaction, uid, command.operationId)?.let { receipt ->
            if (receipt.fingerprint != fingerprint) throw ReliableCommandConflictException("草稿操作标识已用于不同请求")
            // An owner may release a draft after leaving, but an old clear receipt is not a read capability.
            if (current.content != null) repository.requireAccess(transaction, uid, command.chatId)
            return ChatDraftMutationResult(receipt.applied, receipt.revision, current)
        }
        repository.requireReceiptCapacity(transaction, uid, clock())
        if (current.revision != command.expectedRevision) {
            // A deliberately wrong clear revision must not return the inaccessible body's CAS snapshot.
            if (current.content != null) repository.requireAccess(transaction, uid, command.chatId)
            repository.record(transaction, uid, command.operationId, command.issuedAt, ChatDraftReceipt(fingerprint, false, 0))
            return ChatDraftMutationResult(false, 0, current)
        }
        return null
    }

    private fun available(snapshot: ChatDraftSnapshot) = snapshot.copy(assetsAvailable =
        snapshot.content?.assets.orEmpty().flatMap { it.attachments() }.all { catalog.getAttachment(it.path) == it })
}
