package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.client.MessagePager
import com.virjar.tk.shared.client.OptimisticMessageEditLease
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.CompletableDeferred

/** 至多发布一个异步获取的 pager，并使退役幂等。 */
internal class AsyncMessagePagerOwner {
    private val lock = Any()
    /** 只有在 pager 的同步 SQLite 快照到达 ViewModel 状态之后才完成。 */
    private val ready = CompletableDeferred<Unit>()
    private var open = true
    private var pager: MessagePager? = null
    private var collecting = false
    private var closeAllowed = false

    val acceptsResource: Boolean get() = synchronized(lock) { open }

    fun install(candidate: MessagePager): Boolean {
        val installed = synchronized(lock) {
            if (!open || pager != null) return@synchronized false
            pager = candidate
            true
        }
        return installed
    }

    suspend fun awaitReady() {
        ready.await()
    }

    fun current(): MessagePager? = synchronized(lock) { if (open) pager else null }

    fun beginCollection(candidate: MessagePager): Boolean = synchronized(lock) {
        if (!open || pager !== candidate || collecting) return@synchronized false
        collecting = true
        true
    }

    /** 退役等待一次在途的发布；在 [retire] 返回之后没有任何发布可以启动。 */
    fun publishIfOpen(candidate: MessagePager, publish: () -> Unit): Boolean = synchronized(lock) {
        if (!open || pager !== candidate || !collecting) return@synchronized false
        publish()
        true
    }

    /** 第一次投影是比租约安装更强的屏障：离线 focus 读取这个状态。 */
    fun publishMessagesIfOpen(candidate: MessagePager, publish: () -> Unit): Boolean {
        val published = publishIfOpen(candidate, publish)
        if (published) ready.complete(Unit)
        return published
    }

    /** 只有在 coroutineScope 已经 join 两个 pager 收集器之后才调用。 */
    fun finishCollection(candidate: MessagePager): MessagePager? = synchronized(lock) {
        if (pager !== candidate || !collecting) return@synchronized null
        collecting = false
        if (open || closeAllowed) detachLocked() else null
    }

    fun retire() = synchronized(lock) {
        if (!open) return@synchronized
        open = false
    }

    /** 回滚命令先被准入；只有那时，收集器完成才能发布关闭。 */
    fun allowClose(): MessagePager? = synchronized(lock) {
        closeAllowed = true
        if (!collecting) detachLocked() else null
    }

    private fun detachLocked(): MessagePager? = pager.also { pager = null }
}

/**
 * 把乐观消息编辑与 ViewModel 退役线性化。
 *
 * 驻留专属 overlay 和它的回滚租约在同一个监视器下安装。退役关闭准入，
 * 并把每一个租约分离给确切 session 的本地写者；迟到的 RPC 延续
 * 随后找不到任何 token，不可能触碰已退役的缓存或后继组合。
 */
internal class OptimisticMessageEditRetirement(
    private val reserve: (Message) -> OptimisticMessageEditLease?,
    private val publish: (OptimisticMessageEditLease) -> Boolean,
    private val commitLease: (OptimisticMessageEditLease) -> Boolean,
    private val rollbackLease: (OptimisticMessageEditLease) -> Boolean,
) {
    internal class Token internal constructor(internal val id: Long)

    private val lock = Any()
    private var open = true
    private var nextId = 0L
    private val pending = linkedMapOf<Long, OptimisticMessageEditLease>()

    fun begin(optimistic: Message): OptimisticMessageEditAdmission = synchronized(lock) {
        if (!open) return@synchronized OptimisticMessageEditAdmission.Retired
        check(pending.size < MAX_PENDING_EDITS) { "Too many concurrent optimistic message edits" }
        check(nextId < Long.MAX_VALUE) { "Optimistic message edit sequence exhausted" }
        val lease = reserve(optimistic) ?: return@synchronized OptimisticMessageEditAdmission.Rejected
        val token = Token(++nextId)
        pending[token.id] = lease
        try {
            if (!publish(lease)) {
                pending.remove(token.id)
                rollbackLease(lease)
                return@synchronized if (open) {
                    OptimisticMessageEditAdmission.Rejected
                } else {
                    OptimisticMessageEditAdmission.Retired
                }
            }
        } catch (failure: Throwable) {
            pending.remove(token.id)
            try {
                rollbackLease(lease)
            } catch (rollbackFailure: Throwable) {
                if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
        if (open && pending[token.id] === lease) {
            OptimisticMessageEditAdmission.Started(token)
        } else {
            OptimisticMessageEditAdmission.Retired
        }
    }

    fun commit(token: Token): Boolean = synchronized(lock) {
        val lease = pending.remove(token.id) ?: return@synchronized false
        commitLease(lease)
    }

    fun rollback(token: Token): Boolean = synchronized(lock) {
        val lease = pending.remove(token.id) ?: return@synchronized false
        rollbackLease(lease)
    }

    /** 关闭准入，并把每一个挂起的回滚租约转移给 session 写者。 */
    fun detachForRetirement(): List<OptimisticMessageEditLease> = synchronized(lock) {
        if (!open) return@synchronized emptyList()
        open = false
        pending.values.toList().asReversed().also {
            pending.clear()
        }
    }

    /** 已经在 Main 之外执行退役的非 UI owner 的测试/辅助路径。 */
    fun retire(): List<Throwable> = buildList {
        detachForRetirement().forEach { lease ->
            try {
                rollbackLease(lease)
            } catch (failure: Throwable) {
                add(failure)
            }
        }
    }

    private companion object {
        const val MAX_PENDING_EDITS = 16
    }
}

internal sealed interface OptimisticMessageEditAdmission {
    data class Started(val token: OptimisticMessageEditRetirement.Token) : OptimisticMessageEditAdmission
    data object Rejected : OptimisticMessageEditAdmission
    data object Retired : OptimisticMessageEditAdmission
}
