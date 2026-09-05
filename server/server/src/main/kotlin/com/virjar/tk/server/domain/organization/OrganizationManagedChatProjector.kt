package com.virjar.tk.server.domain.organization

import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.ManagedChatProjectionCache
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

enum class OrganizationProjectionStage {
    BEFORE_APPLY,
    AFTER_APPLY_BEFORE_EVENT_FLUSH,
}

fun interface OrganizationProjectionHooks {
    fun hit(stage: OrganizationProjectionStage, task: ManagedChatProjectionTask)

    object None : OrganizationProjectionHooks {
        override fun hit(stage: OrganizationProjectionStage, task: ManagedChatProjectionTask) = Unit
    }
}

data class OrganizationProjectionDrainResult(
    val attempted: Int,
    val failures: Set<String>,
    val remaining: Long,
)

/** 在聊天生命周期闸门之后应用一次完整的受管聊天替换。 */
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
                val recipientEvents = mutation.recipientEvents()
                val chat = mutation.chat
                check(chat != null || recipientEvents.isEmpty()) {
                    "Applied organization projection has no chat snapshot"
                }

                if (chat != null) {
                    recipientEvents.forEach { event ->
                        appendEvent(
                            event.uid,
                            event.type,
                            chat,
                        )
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

    private companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 500
    }
}

private data class OrganizationRecipientEvent(
    val uid: String,
    val type: NotifyType,
)

/**
 * 一次受管聊天修订对每个接收者至多产生一个事件。每个事件携带相同的最终 Chat 快照，
 * 因此即使一次修订同时改变了成员、角色与元数据，最具体的刷新提示也已足够。
 */
private fun ManagedChatProjectionMutation.recipientEvents(): List<OrganizationRecipientEvent> {
    val added = addedUids.toSet()
    val removed = removedUids.toSet()
    val remaining = remainingUids.toSet()
    val roleChanged = roleChanges.mapTo(linkedSetOf()) { it.uid }
    check(added.size == addedUids.size && removed.size == removedUids.size &&
        remaining.size == remainingUids.size && roleChanged.size == roleChanges.size
    ) { "Organization projection mutation contains duplicate recipients" }
    check(added.intersect(removed).isEmpty() && added.intersect(remaining).isEmpty() &&
        removed.intersect(remaining).isEmpty()
    ) { "Organization projection mutation recipient classes overlap" }
    check(remaining.containsAll(roleChanged)) {
        "Organization projection role changes must belong to remaining members"
    }
    check(roleChanges.all { it.previousRole != it.currentRole }) {
        "Organization projection role changes must describe an actual transition"
    }

    val remainingType = when {
        roleChanged.isNotEmpty() -> NotifyType.MEMBER_ROLE_CHANGED
        added.isNotEmpty() -> NotifyType.MEMBER_ADDED
        removed.isNotEmpty() -> NotifyType.MEMBER_REMOVED
        chatMetadataChanged -> NotifyType.CHAT_UPDATED
        else -> null
    }
    return buildList {
        removedUids.forEach { add(OrganizationRecipientEvent(it, NotifyType.CHAT_DELETED)) }
        addedUids.forEach { add(OrganizationRecipientEvent(it, NotifyType.CHAT_CREATED)) }
        if (remainingType != null) {
            remainingUids.forEach { add(OrganizationRecipientEvent(it, remainingType)) }
        }
    }
}

class OrganizationProjectionReadiness(
    private val store: OrganizationManagedChatProjectionStore,
) {
    fun pendingCount(): Long = store.countPending()
    fun currentFailure(): ManagedChatProjectionFailure? = store.currentFailure()
}
