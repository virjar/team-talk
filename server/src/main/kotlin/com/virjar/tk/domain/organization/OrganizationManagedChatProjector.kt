package com.virjar.tk.domain.organization

import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.chat.ManagedChatProjectionCache
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

enum class OrganizationProjectionStage {
    BEFORE_APPLY,
    AFTER_APPLY_BEFORE_EVENT_FLUSH,
}

fun interface OrganizationProjectionHooks {
    suspend fun hit(stage: OrganizationProjectionStage, task: ManagedChatProjectionTask)

    object None : OrganizationProjectionHooks {
        override suspend fun hit(stage: OrganizationProjectionStage, task: ManagedChatProjectionTask) = Unit
    }
}

data class OrganizationProjectionDrainResult(
    val attempted: Int,
    val failures: Set<String>,
    val remaining: Long,
)

/** Applies one complete managed-chat replacement behind the chat lifecycle gate. */
class OrganizationManagedChatProjector(
    private val store: OrganizationManagedChatProjectionStore,
    private val lifecycleGate: ChatLifecycleGate,
    private val unitOfWork: PgUnitOfWork,
    private val cache: ManagedChatProjectionCache,
    private val hooks: OrganizationProjectionHooks = OrganizationProjectionHooks.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(OrganizationManagedChatProjector::class.java)

    suspend fun project(task: ManagedChatProjectionTask): Boolean = lifecycleGate.withChat(task.chatId) {
        try {
            hooks.hit(OrganizationProjectionStage.BEFORE_APPLY, task)
            unitOfWork.write {
                val mutation = store.apply(transaction, task)
                if (!mutation.applied) return@write
                val requiresPayload = mutation.addedUids.isNotEmpty() ||
                    mutation.removedUids.isNotEmpty() || mutation.finalUids.isNotEmpty()
                val chat = mutation.chat
                check(chat != null || !requiresPayload) {
                    "Applied organization projection has no chat snapshot"
                }

                if (chat != null) {
                    mutation.addedUids.forEach { uid ->
                        appendEvent(uid, NotifyType.CHAT_CREATED, chat, dedupe(task, uid, "chat-created"))
                    }
                    mutation.removedUids.forEach { uid ->
                        appendEvent(uid, NotifyType.CHAT_DELETED, chat, dedupe(task, uid, "chat-deleted"))
                        appendEvent(
                            uid,
                            NotifyType.CONVERSATION_DELETED,
                            Conversation(chatId = task.chatId, chatType = 0),
                            dedupe(task, uid, "conversation-deleted"),
                        )
                    }
                    if (mutation.addedUids.isNotEmpty()) {
                        mutation.remainingUids.forEach { uid ->
                            appendEvent(uid, NotifyType.MEMBER_ADDED, chat, dedupe(task, uid, "member-added"))
                        }
                    }
                    if (mutation.removedUids.isNotEmpty()) {
                        mutation.remainingUids.forEach { uid ->
                            appendEvent(uid, NotifyType.MEMBER_REMOVED, chat, dedupe(task, uid, "member-removed"))
                        }
                    }
                    mutation.finalUids.forEach { uid ->
                        appendEvent(uid, NotifyType.CHAT_UPDATED, chat, dedupe(task, uid, "chat-updated"))
                    }
                }
                hooks.hit(OrganizationProjectionStage.AFTER_APPLY_BEFORE_EVENT_FLUSH, task)
                afterCommit { cache.invalidateManagedChat(task.chatId) }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val detail = error.message?.take(900) ?: error::class.simpleName.orEmpty()
            try {
                unitOfWork.write { store.recordFailure(transaction, task, detail, clock()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                fatal.addSuppressed(error)
                throw fatal
            } catch (recordFailure: Exception) {
                error.addSuppressed(recordFailure)
            }
            logger.error(
                "Failed to project managed organization chat unitId={} revision={}",
                task.unitId,
                task.revision,
                error,
            )
            false
        }
    }

    suspend fun drainPending(
        includeDeferred: Boolean = false,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): OrganizationProjectionDrainResult {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE" }
        var cursor: ManagedChatProjectionCursor? = null
        var attempted = 0
        val failures = linkedSetOf<String>()
        while (true) {
            val page = store.listPending(cursor, pageSize, includeDeferred, clock())
            if (page.isEmpty()) break
            page.forEach { task ->
                attempted += 1
                if (!project(task)) failures += task.unitId
            }
            val last = page.last()
            cursor = ManagedChatProjectionCursor(last.revision, last.unitId)
        }
        val remaining = store.countPending()
        if (remaining > 0) failures += pendingUnitIds()
        return OrganizationProjectionDrainResult(attempted, failures, remaining)
    }

    private fun pendingUnitIds(): Set<String> {
        var cursor: ManagedChatProjectionCursor? = null
        val result = linkedSetOf<String>()
        while (true) {
            val page = store.listPending(cursor, MAX_PAGE_SIZE, includeDeferred = true, nowMillis = clock())
            if (page.isEmpty()) return result
            result += page.map(ManagedChatProjectionTask::unitId)
            val last = page.last()
            cursor = ManagedChatProjectionCursor(last.revision, last.unitId)
        }
    }

    private fun dedupe(task: ManagedChatProjectionTask, uid: String, kind: String): String =
        "org:${task.unitId}:${task.revision}:$uid:$kind"

    private companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 500
    }
}

class OrganizationProjectionReadiness(
    private val store: OrganizationManagedChatProjectionStore,
) {
    fun pendingCount(): Long = store.countPending()
    fun currentFailure(): ManagedChatProjectionFailure? = store.currentFailure()
}
