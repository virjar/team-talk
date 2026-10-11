package com.virjar.tk.app.navigation.feature.document

/**
 * 平台各文档写入方共享的、按 owner 有界且只保留最新项的准入队列。
 * 调用方自持状态锁与单一磁盘写入者；取出条目并不退役其
 * generation。因此删除或更新的快照可能使一个正在编码的条目失效。
 * 载荷保持惰性，准入一帧编辑器状态从不编码文档正文。
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
        // Native 侧 map 条目在结构性变更（包括移除该 key）后即失效。
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

    /** 只在磁盘排空已空闲时调用；在途的 generation 必须保留自己的围栏。 */
    fun forgetIdleOwnersExcept(owner: DocumentDraftOwnerKey? = null) {
        check(isEmpty)
        if (owner == null) generations.clear() else generations.keys.retainAll(setOf(owner))
    }
}
