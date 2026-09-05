package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * 拥有常驻消息窗口身份、有界 LRU 准入与精确 pager 租约。持久状态留在 [LocalMessageStore]；
 * 调用方只有在共享缓存 [stateLock] 之后才进入该注册表，为快照发布与缓存退役保持一个加锁顺序。
 */
internal class LocalMessageWindowRegistry(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val historyLeases: MessageHistoryLeaseGate,
    private val pruneIdleWindowTail: (String) -> Boolean,
) {
    // 写者发布进一个已经常驻的窗口，而不持有 registryLock。条目准入/移除与每个可变租约/LRU
    // 字段仍归 registryLock 所有。
    private val windows = ConcurrentHashMap<String, ResidentMessageWindow>()
    private val lru = LinkedHashMap<String, Long>(LocalCache.MAX_ACTIVE_CHATS, 0.75f, true)
    private val registryLock = Any()
    private var nextWindowGeneration = 0L
    private var nextWindowLeaseId = 0L

    internal var snapshotLoadedHookForTest: (() -> Unit)? = null

    fun residentWindow(chatId: String): MessageWindow? = windows[chatId]?.window

    /** 当加锁顺序重要时，调用方已经持有缓存状态锁。 */
    fun isResident(chatId: String): Boolean = synchronized(registryLock) {
        windows.containsKey(chatId)
    }

    /** 把驱逐挡在历史 SQL 事务及其匹配的常驻发布之外。 */
    fun <T> withResidentWindow(chatId: String, block: (MessageWindow?) -> T): T =
        synchronized(registryLock) { block(windows[chatId]?.window) }

    fun acquire(chatId: String, windowSize: Int): MessagePager {
        require(chatId.isNotBlank()) { "chatId must not be blank" }
        require(windowSize in 1..MessagePager.MAX_WINDOW_SIZE) {
            "windowSize must be between 1 and ${MessagePager.MAX_WINDOW_SIZE}"
        }
        // 一行要么在这个快照里，要么拥有 stateLock 的写者观察到已注册窗口并在提交之后发布
        // 同一事实。
        synchronized(stateLock) {
            synchronized(registryLock) {
                val existing = windows[chatId]
                val entry = if (existing != null) {
                    require(existing.windowSize == windowSize) {
                        "chat $chatId already has a ${existing.windowSize}-message resident window"
                    }
                    existing
                } else {
                    evictIdleWindowForAdmission()
                    nextWindowGeneration = nextCounter(
                        nextWindowGeneration,
                        "message window generation",
                    )
                    val window = MessageWindow(
                        chatId,
                        queries,
                        cacheUseGate,
                        windowSize,
                    ) { it.toLocalModel() }
                    snapshotLoadedHookForTest?.invoke()
                    ResidentMessageWindow(
                        chatId = chatId,
                        generation = nextWindowGeneration,
                        windowSize = windowSize,
                        window = window,
                    ).also { created -> windows[chatId] = created }
                }
                historyLeases.retain(chatId)
                lru[chatId] = System.currentTimeMillis()
                nextWindowLeaseId = nextCounter(nextWindowLeaseId, "message window lease id")
                val leaseId = nextWindowLeaseId
                val leaseState = entry.window.acquireLease(leaseId)
                val pager = LocalMessagePagerLease(
                    cacheUseGate = cacheUseGate,
                    window = entry.window,
                    leaseState = leaseState,
                    release = {
                        releaseWindowLease(
                            chatId = chatId,
                            entryGeneration = entry.generation,
                            entry = entry,
                            leaseId = leaseId,
                        )
                    },
                )
                check(entry.activeLeases.put(leaseId, pager) == null) {
                    "Duplicate active message window lease"
                }
                return pager
            }
        }
    }

    /** 调用方持有 [stateLock]；让现有收集者保持附着在已加墓碑的窗口上。 */
    fun resetChat(chatId: String) = synchronized(registryLock) {
        windows[chatId]?.window?.resetServerProjection()
    }

    /** 调用方持有 [stateLock] 与 reset 事务；每个窗口贡献一次有界读取。 */
    fun snapshotForReset(): List<MessageWindowResetSnapshot> = synchronized(registryLock) {
        windows.values.map { entry ->
            MessageWindowResetSnapshot(
                chatId = entry.chatId,
                windowGeneration = entry.generation,
                messages = loadBoundedInitialMessages(
                    queries = queries,
                    chatId = entry.chatId,
                    limit = entry.windowSize,
                    toModel = { row -> row.toLocalModel() },
                ),
            )
        }
    }

    /** 调用方持有 [stateLock]；发布已提交的 reset，而不替换 Flow 身份。 */
    fun resetAll(snapshot: List<MessageWindowResetSnapshot>) = synchronized(registryLock) {
        snapshot.forEach { restored ->
            // 同步收集者可以在另一个窗口发布时重入注册表。
            // 替代窗口加载了已经提交的 SQLite reset，无需重放。
            val entry = windows[restored.chatId]
                ?.takeIf { it.generation == restored.windowGeneration }
                ?: return@forEach
            entry.window.resetServerProjection()
            restored.messages.forEach(entry.window::upsert)
        }
    }

    /** 调用方在终态缓存退役期间持有 [stateLock]。 */
    fun closeAll() = synchronized(registryLock) {
        windows.values.forEach { entry ->
            entry.activeLeases.values.toList().forEach { pager -> pager.retireFromCache() }
            entry.window.retireAllLeases()
            entry.activeLeases.clear()
        }
        windows.clear()
        lru.clear()
    }

    /** 调用方持有 [stateLock]。 */
    fun counts(): MessageWindowResidentCounts = synchronized(registryLock) {
        MessageWindowResidentCounts(
            totalWindows = windows.size,
            activeWindows = windows.values.count { it.activeLeases.isNotEmpty() },
            activeLeases = windows.values.sumOf { it.activeLeases.size },
        )
    }

    /** 调用方持有 [stateLock]；只有当保留删除了其尾部时才退役一个闲置快照。 */
    fun sweepIdleWindowForRetention(chatId: String): Boolean = synchronized(registryLock) {
        retireIdleWindowAfterRetention(chatId)
    }

    private fun releaseWindowLease(
        chatId: String,
        entryGeneration: Long,
        entry: ResidentMessageWindow,
        leaseId: Long,
    ) {
        cacheUseGate.runIfOpen {
            synchronized(stateLock) {
                synchronized(registryLock) {
                    check(entry.chatId == chatId && entry.generation == entryGeneration) {
                        "Message window lease identity mismatch"
                    }
                    val wasPublished = entry.window.releaseLease(leaseId)
                    val wasActive = entry.activeLeases.remove(leaseId) != null
                    check(wasPublished == wasActive) { "Message window lease ownership diverged" }
                    val current = windows[chatId]
                    if (current === entry && current.generation == entryGeneration && wasActive) {
                        // 定义闲置 LRU 顺序的是最后一次真实使用，而不是构造。
                        lru[chatId] = System.currentTimeMillis()
                        if (entry.activeLeases.isEmpty()) retireIdleWindowAfterRetention(chatId)
                    }
                }
            }
            true
        }
    }

    /** 在最后一个 pager 租约消失之后，调用方持有 [registryLock]。 */
    private fun retireIdleWindowAfterRetention(chatId: String): Boolean {
        val entry = windows[chatId] ?: return false
        if (entry.activeLeases.isNotEmpty() || !pruneIdleWindowTail(chatId)) return false
        check(windows.remove(chatId) === entry) { "Idle message window identity changed" }
        lru.remove(chatId)
        entry.window.retireAllLeases()
        historyLeases.release(chatId)
        return true
    }

    /** 调用方持有 [stateLock] 与 [registryLock]。活跃条目绝不会成为驱逐候选。 */
    private fun evictIdleWindowForAdmission() {
        if (windows.size < LocalCache.MAX_ACTIVE_CHATS) return
        val oldestIdleChatId = lru.keys.firstOrNull { chatId ->
            windows[chatId]?.activeLeases?.isEmpty() == true
        } ?: throw MessageWindowCapacityExceededException(LocalCache.MAX_ACTIVE_CHATS)
        val evicted = checkNotNull(windows.remove(oldestIdleChatId))
        check(evicted.activeLeases.isEmpty()) { "Cannot evict an active message window" }
        lru.remove(oldestIdleChatId)
        evicted.window.retireAllLeases()
        historyLeases.release(oldestIdleChatId)
    }

    private fun nextCounter(current: Long, label: String): Long {
        check(current < Long.MAX_VALUE) { "$label exhausted" }
        return current + 1L
    }
}

internal data class MessageWindowResidentCounts(
    val totalWindows: Int,
    val activeWindows: Int,
    val activeLeases: Int,
)

internal data class MessageWindowResetSnapshot(
    val chatId: String,
    val windowGeneration: Long,
    val messages: List<Message>,
)

private class ResidentMessageWindow(
    val chatId: String,
    val generation: Long,
    val windowSize: Int,
    val window: MessageWindow,
) {
    val activeLeases = linkedMapOf<Long, LocalMessagePagerLease>()
}

/** 每次获取代理：close 是精确的，不能退役另一个租约或后继条目。 */
private class LocalMessagePagerLease(
    private val cacheUseGate: CacheUseGate,
    window: MessageWindow,
    leaseState: MessageWindowLeaseState,
    release: () -> Unit,
) : MessagePager {
    private val ownerLock = Any()
    private var open = true
    private var windowOwner: MessageWindow? = window
    private var releaseOwner: (() -> Unit)? = release

    override val messages = leaseState.messages
    override val hasMore = leaseState.hasMore

    override fun loadMore(pageSize: Int): MessagePageLoadResult = cacheUseGate.use {
        synchronized(ownerLock) {
            check(open) { "MessagePager is closed" }
            require(pageSize in 1..MessagePager.MAX_PAGE_SIZE) {
                "pageSize must be between 1 and ${MessagePager.MAX_PAGE_SIZE}"
            }
            checkNotNull(windowOwner) { "MessagePager window owner is released" }.loadMore(pageSize)
        }
    }

    override fun close() {
        val release = synchronized(ownerLock) {
            if (!open) null else {
                open = false
                windowOwner = null
                releaseOwner.also { releaseOwner = null }
            }
        }
        release?.invoke()
    }

    /** 缓存关闭已经拥有缓存门禁，并自行移除匹配的存储条目。 */
    fun retireFromCache() = synchronized(ownerLock) {
        if (!open) return@synchronized
        open = false
        windowOwner = null
        releaseOwner = null
    }
}
