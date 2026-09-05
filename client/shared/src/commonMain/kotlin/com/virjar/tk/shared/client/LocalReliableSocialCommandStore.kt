package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries

/** 一个 deployment/account LocalCache 拥有的有界持久命令可靠发件箱。 */
internal class LocalReliableSocialCommandStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    private var contactState: ContactCommandStoreState = loadContactState()
    private var inviteState: InviteCommandStoreState = loadInviteState()

    fun prepareContact(candidate: PendingContactDecision): PendingContactDecision = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyContactsLocked()
            val canonical = candidate.requireCanonical()
            healthy.byToken[canonical.token]?.let { existing ->
                if (!existing.hasSamePayload(canonical)) {
                    throw PendingReliableCommandConflictException(
                        "该好友申请已有另一项未确认处理操作",
                    )
                }
                return@synchronized existing
            }
            if (healthy.byToken.values.any { it.operationId == canonical.operationId }) {
                throw PendingReliableCommandConflictException("好友申请操作标识已用于其他请求")
            }
            check(healthy.byToken.size < MAX_PENDING_CONTACT_DECISIONS) {
                "待确认好友申请操作数量已达上限"
            }
            queries.insertPendingContactDecision(
                canonical.operationId,
                canonical.token,
                canonical.decision.code,
                canonical.createdAt,
            )
            healthy.byToken[canonical.token] = canonical
            canonical
        }
    }

    fun contacts(): List<PendingContactDecision> = cacheUseGate.use {
        synchronized(stateLock) { healthyContactsLocked().byToken.values.toList() }
    }

    fun clearContact(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyContactsLocked()
            val entry = healthy.byToken.entries.firstOrNull {
                it.value.operationId == operationId
            } ?: return@synchronized false
            queries.deletePendingContactDecision(operationId)
            healthy.byToken.remove(entry.key)
            true
        }
    }

    fun prepareInvite(candidate: PendingInviteLinkCreation): PendingInviteLinkCreation = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyInvitesLocked()
            val canonical = candidate.requireCanonical()
            healthy.byChat[canonical.chatId]?.let { existing ->
                if (!existing.hasSamePayload(canonical)) {
                    throw PendingReliableCommandConflictException(
                        "该群已有另一项未确认邀请链接创建操作",
                    )
                }
                return@synchronized existing
            }
            if (healthy.byChat.values.any { it.operationId == canonical.operationId }) {
                throw PendingReliableCommandConflictException("邀请链接操作标识已用于其他请求")
            }
            check(healthy.byChat.size < MAX_PENDING_INVITE_LINK_CREATIONS) {
                "待确认邀请链接操作数量已达上限"
            }
            queries.insertPendingInviteLinkCreation(
                canonical.operationId,
                canonical.chatId,
                canonical.name,
                canonical.maxUses.toLong(),
                canonical.expiresAt,
                canonical.createdAt,
            )
            healthy.byChat[canonical.chatId] = canonical
            canonical
        }
    }

    fun invites(): List<PendingInviteLinkCreation> = cacheUseGate.use {
        synchronized(stateLock) { healthyInvitesLocked().byChat.values.toList() }
    }

    fun clearInvite(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyInvitesLocked()
            val entry = healthy.byChat.entries.firstOrNull {
                it.value.operationId == operationId
            } ?: return@synchronized false
            queries.deletePendingInviteLinkCreation(operationId)
            healthy.byChat.remove(entry.key)
            true
        }
    }

    private fun loadContactState(): ContactCommandStoreState {
        // SQL/打开失败是缓存构造失败。只有读取成功但违反该不可变命令族的行才毒化其自己的有界存储。
        val contactRows = queries.selectPendingContactDecisions().executeAsList()
        return try {
            val contacts = contactRows.map { row ->
                PendingContactDecision(
                    operationId = row.operation_id,
                    token = row.token,
                    decision = PendingContactDecisionType.fromCode(row.decision),
                    createdAt = row.created_at,
                ).requireCanonical()
            }
            check(contacts.size <= MAX_PENDING_CONTACT_DECISIONS) {
                "Persisted contact-decision outbox exceeds its fixed capacity"
            }
            val byToken = contacts.associateByTo(linkedMapOf(), PendingContactDecision::token)
            check(byToken.size == contacts.size) { "Persisted contact-decision resources are duplicated" }
            ContactCommandStoreState.Healthy(byToken)
        } catch (corrupt: IllegalStateException) {
            ContactCommandStoreState.Poisoned(
                CorruptReliableSocialCommandException("好友申请处理", corrupt),
            )
        }
    }

    private fun loadInviteState(): InviteCommandStoreState {
        val inviteRows = queries.selectPendingInviteLinkCreations().executeAsList()
        return try {
            val invites = inviteRows.map { row ->
                PendingInviteLinkCreation(
                    operationId = row.operation_id,
                    chatId = row.chat_id,
                    name = row.name,
                    maxUses = row.max_uses.toIntExact("max uses"),
                    expiresAt = row.expires_at,
                    createdAt = row.created_at,
                ).requireCanonical()
            }
            check(invites.size <= MAX_PENDING_INVITE_LINK_CREATIONS) {
                "Persisted invite-creation outbox exceeds its fixed capacity"
            }
            val byChat = invites.associateByTo(linkedMapOf(), PendingInviteLinkCreation::chatId)
            check(byChat.size == invites.size) { "Persisted invite-creation resources are duplicated" }
            InviteCommandStoreState.Healthy(byChat)
        } catch (corrupt: IllegalStateException) {
            InviteCommandStoreState.Poisoned(
                CorruptReliableSocialCommandException("邀请链接创建", corrupt),
            )
        }
    }

    private fun healthyContactsLocked(): ContactCommandStoreState.Healthy = when (val current = contactState) {
        is ContactCommandStoreState.Healthy -> current
        is ContactCommandStoreState.Poisoned -> throw current.failure
    }

    private fun healthyInvitesLocked(): InviteCommandStoreState.Healthy = when (val current = inviteState) {
        is InviteCommandStoreState.Healthy -> current
        is InviteCommandStoreState.Poisoned -> throw current.failure
    }
}

private fun Long.toIntExact(label: String): Int {
    check(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Persisted $label is out of range" }
    return toInt()
}

internal class CorruptReliableSocialCommandException(label: String, cause: Throwable) : IllegalStateException(
    "本地${label}可靠命令记录损坏，已禁止覆盖未知操作",
    cause,
)

private sealed interface ContactCommandStoreState {
    data class Healthy(val byToken: LinkedHashMap<String, PendingContactDecision>) : ContactCommandStoreState

    data class Poisoned(val failure: CorruptReliableSocialCommandException) : ContactCommandStoreState
}

private sealed interface InviteCommandStoreState {
    data class Healthy(val byChat: LinkedHashMap<String, PendingInviteLinkCreation>) : InviteCommandStoreState

    data class Poisoned(val failure: CorruptReliableSocialCommandException) :
        InviteCommandStoreState
}
