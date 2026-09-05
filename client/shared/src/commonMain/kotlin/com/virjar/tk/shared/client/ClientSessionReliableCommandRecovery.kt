package com.virjar.tk.shared.client

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.repository.ChatRepository
import com.virjar.tk.shared.repository.ContactRepository
import com.virjar.tk.shared.repository.DocumentMoveCommandCompletion
import com.virjar.tk.shared.repository.DocumentRepository
import com.virjar.tk.shared.repository.GroupFileCommandCompletion
import com.virjar.tk.shared.repository.GroupFileRepository
import com.virjar.tk.shared.repository.RecoveredContactDecision
import com.virjar.tk.shared.repository.retryIndependentPendingFamilies
import com.virjar.tk.protocol.rpc.RpcInvoker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 前台仓库与已恢复 UI owner 共享的有界完成台账。 */
internal class SessionReliableCommandCompletionFlows private constructor(
    val inviteLinks: MutableSharedFlow<String>,
    val contactDecisions: MutableSharedFlow<RecoveredContactDecision>,
    val groupFiles: MutableSharedFlow<GroupFileCommandCompletion>,
    val documentMoves: MutableSharedFlow<DocumentMoveCommandCompletion>,
) {
    companion object {
        fun create(): SessionReliableCommandCompletionFlows =
            SessionReliableCommandCompletionFlows(
                inviteLinks = reliableCommandRecoveryCompletionFlow(
                    MAX_PENDING_INVITE_LINK_CREATIONS,
                ),
                contactDecisions = reliableCommandRecoveryCompletionFlow(
                    MAX_PENDING_CONTACT_DECISIONS,
                ),
                groupFiles = reliableCommandRecoveryCompletionFlow(
                    MAX_PENDING_GROUP_FILE_COMMANDS,
                ),
                documentMoves = reliableCommandRecoveryCompletionFlow(
                    MAX_PENDING_DOCUMENT_MOVE_COMMANDS,
                ),
            )
    }
}

/** 共享会话恢复 worker 及其唤醒策略的可靠命令族。 */
internal class SessionReliableCommandFamilies private constructor(
    val contacts: ContactRepository,
    val chats: ChatRepository,
    val groupFiles: GroupFileRepository,
    val documents: DocumentRepository,
    private val localCache: LocalCache,
) {
    suspend fun retryPending(): Outcome<Unit> = retryIndependentPendingFamilies(
        contacts::retryPendingDecisions,
        chats::retryPendingInviteLinkCreations,
        groupFiles::retryPendingCommands,
        documents::retryPendingMoveCommands,
    )

    fun nextExpiryAt(): Long? {
        val socialExpiry = nextReliableSocialCommandExpiryAt(
            contactDecisions = localCache.getPendingContactDecisions(),
            inviteLinkCreations = localCache.getPendingInviteLinkCreations(),
        )
        val documentExpiry = nextDocumentMoveCommandExpiryAt(
            localCache.getPendingDocumentMoveCommands(),
        )
        return listOfNotNull(socialExpiry, documentExpiry).minOrNull()
    }

    companion object {
        fun create(
            rpcClient: RpcInvoker,
            localCache: LocalCache,
            ownerUid: String,
            completions: SessionReliableCommandCompletionFlows,
            onPendingCommitted: () -> Unit,
        ): SessionReliableCommandFamilies {
            val contacts = ContactRepository(
                rpcClient = rpcClient,
                localCache = localCache,
                onPendingReliableCommandCommitted = onPendingCommitted,
                onPendingContactDecisionRecovered = { completion ->
                    completions.contactDecisions.tryEmit(completion)
                },
            )
            val chats = ChatRepository(
                rpcClient = rpcClient,
                localCache = localCache,
                ownerUid = ownerUid,
                onPendingReliableCommandCommitted = onPendingCommitted,
                onPendingInviteLinkCreationRecovered = { chatId ->
                    completions.inviteLinks.tryEmit(chatId)
                },
            )
            val groupFiles = GroupFileRepository(
                rpcClient = rpcClient,
                localCache = localCache,
                onPendingReliableCommandCommitted = onPendingCommitted,
                onPendingGroupFileCommandCompleted = { completion ->
                    completions.groupFiles.tryEmit(completion)
                },
            )
            val documents = DocumentRepository(
                rpcClient = rpcClient,
                localCache = localCache,
                onPendingReliableCommandCommitted = onPendingCommitted,
                onPendingMoveCommandCompleted = { completion ->
                    completions.documentMoves.tryEmit(completion)
                },
            )
            return SessionReliableCommandFamilies(
                contacts = contacts,
                chats = chats,
                groupFiles = groupFiles,
                documents = documents,
                localCache = localCache,
            )
        }
    }
}

/** 在其 RPC client 与仓库存活之后启动会话拥有的唯一 worker。 */
internal fun startSessionPendingMirrorRecovery(
    connectionState: StateFlow<ConnectionState>,
    wake: SessionPendingMirrorWake,
    retryPendingDrafts: suspend () -> Outcome<Unit>,
    retryPendingReads: suspend () -> Outcome<Unit>,
    reliableCommands: SessionReliableCommandFamilies,
    parentScope: CoroutineScope,
    onAuthExpired: () -> Unit,
) {
    SessionPendingMirrorRecovery(
        connectionState = connectionState,
        wake = wake,
        retryPendingDrafts = retryPendingDrafts,
        retryPendingReads = retryPendingReads,
        retryPendingReliableCommands = reliableCommands::retryPending,
        nextReliableCommandExpiryAt = reliableCommands::nextExpiryAt,
        parentScope = parentScope,
        onAuthExpired = onAuthExpired,
    )
}
