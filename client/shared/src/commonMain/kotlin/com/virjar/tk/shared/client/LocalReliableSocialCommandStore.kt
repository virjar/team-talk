package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries

/** 一个 deployment/account LocalCache 拥有的有界持久命令可靠发件箱。 */
internal class LocalReliableSocialCommandStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    private val contactSlot =
        PendingCommandSlot(load = ::loadContacts, corrupt = { CorruptReliableSocialCommandException("好友申请处理", it) })
    private val inviteSlot =
        PendingCommandSlot(load = ::loadInvites, corrupt = { CorruptReliableSocialCommandException("邀请链接创建", it) })

    fun prepareContact(candidate: PendingContactDecision): PendingContactDecision = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = contactSlot.healthy()
            val canonical = candidate.requireCanonical()
            healthy[canonical.token]?.let { existing ->
                if (!existing.hasSamePayload(canonical)) {
                    throw PendingReliableCommandConflictException(
                        "该好友申请已有另一项未确认处理操作",
                    )
                }
                return@synchronized existing
            }
            if (healthy.values.any { it.operationId == canonical.operationId }) {
                throw PendingReliableCommandConflictException("好友申请操作标识已用于其他请求")
            }
            check(healthy.size < MAX_PENDING_CONTACT_DECISIONS) {
                "待确认好友申请操作数量已达上限"
            }
            queries.insertPendingContactDecision(
                canonical.operationId,
                canonical.token,
                canonical.decision.code,
                canonical.createdAt,
            )
            healthy[canonical.token] = canonical
            canonical
        }
    }

    fun contacts(): List<PendingContactDecision> = cacheUseGate.use {
        synchronized(stateLock) { contactSlot.healthy().values.toList() }
    }

    fun clearContact(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = contactSlot.healthy()
            val entry = healthy.entries.firstOrNull {
                it.value.operationId == operationId
            } ?: return@synchronized false
            queries.deletePendingContactDecision(operationId)
            healthy.remove(entry.key)
            true
        }
    }

    fun prepareInvite(candidate: PendingInviteLinkCreation): PendingInviteLinkCreation = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = inviteSlot.healthy()
            val canonical = candidate.requireCanonical()
            healthy[canonical.chatId]?.let { existing ->
                if (!existing.hasSamePayload(canonical)) {
                    throw PendingReliableCommandConflictException(
                        "该群已有另一项未确认邀请链接创建操作",
                    )
                }
                return@synchronized existing
            }
            if (healthy.values.any { it.operationId == canonical.operationId }) {
                throw PendingReliableCommandConflictException("邀请链接操作标识已用于其他请求")
            }
            check(healthy.size < MAX_PENDING_INVITE_LINK_CREATIONS) {
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
            healthy[canonical.chatId] = canonical
            canonical
        }
    }

    fun invites(): List<PendingInviteLinkCreation> = cacheUseGate.use {
        synchronized(stateLock) { inviteSlot.healthy().values.toList() }
    }

    fun clearInvite(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = inviteSlot.healthy()
            val entry = healthy.entries.firstOrNull {
                it.value.operationId == operationId
            } ?: return@synchronized false
            queries.deletePendingInviteLinkCreation(operationId)
            healthy.remove(entry.key)
            true
        }
    }

    // SQL/打开失败是缓存构造失败。只有读取成功但违反该不可变命令族的行才毒化其自己的有界存储。
    private fun loadContacts(): LinkedHashMap<String, PendingContactDecision> {
        val contactRows = queries.selectPendingContactDecisions().executeAsList()
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
        return byToken
    }

    private fun loadInvites(): LinkedHashMap<String, PendingInviteLinkCreation> {
        val inviteRows = queries.selectPendingInviteLinkCreations().executeAsList()
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
        return byChat
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
