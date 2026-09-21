package com.virjar.tk.app.navigation.feature.document

/**
 * Bounded, latest-per-owner admission shared by platform document writers.
 * The caller owns its state lock and the single disk writer; taking an entry does not retire its
 * generation. A delete or newer snapshot can therefore invalidate an entry already being encoded.
 * Payloads remain lazy, so admitting an editor frame never encodes document bodies.
 */
class DocumentDraftPendingWrites(private val maximumOwners: Int) {
    data class Write(
        val ownerKey: DocumentDraftOwnerKey,
        val generation: Long,
        val payload: () -> DocumentDraftPayload,
    )

    private val pending = linkedMapOf<DocumentDraftOwnerKey, Write>()
    private val generations = mutableMapOf<DocumentDraftOwnerKey, Long>()

    init { require(maximumOwners > 0) }

    val isEmpty: Boolean get() = pending.isEmpty()
    fun canAccept(owner: DocumentDraftOwnerKey): Boolean = owner in generations || generations.size < maximumOwners

    fun put(owner: DocumentDraftOwnerKey, generation: Long, payload: () -> DocumentDraftPayload) {
        check(canAccept(owner)) { "Too many pending document draft owners" }
        generations[owner] = generation
        pending[owner] = Write(owner, generation, payload)
    }

    fun take(): Write? = pending.entries.firstOrNull()?.let { entry ->
        // Native map entries are invalidated by a structural change, including removing this key.
        val write = entry.value
        pending.remove(write.ownerKey)
        write
    }

    fun isCurrent(write: Write): Boolean = generations[write.ownerKey] == write.generation

    fun complete(write: Write) {
        if (isCurrent(write) && pending[write.ownerKey]?.generation != write.generation) {
            generations.remove(write.ownerKey)
        }
    }

    fun invalidate(owner: DocumentDraftOwnerKey, generation: Long) {
        generations[owner] = generation
        pending.remove(owner)
    }

    fun clear() { pending.clear(); generations.clear() }

    /** Only call once the disk drain is idle; an in-flight generation must retain its fence. */
    fun forgetIdleOwnersExcept(owner: DocumentDraftOwnerKey? = null) {
        check(isEmpty)
        if (owner == null) generations.clear() else generations.keys.retainAll(setOf(owner))
    }
}
