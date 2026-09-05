package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.EventProcessor
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import com.virjar.tk.shared.testkit.FakeLocalCache

/**
 * 真实 TCP 客户端必须完成与生产客户端相同的持久事件握手。
 * 不需要完整 ClientSession 的测试仍然安装真实的 EventProcessor，
 * 而不是绕过同步或静默丢弃重放事件。FakeLocalCache 的默认 dataset
 * 被有意禁用：每个测试夹具都必须绑定精确的服务器权威身份。
 */
internal class E2eEventProjection(
    private val client: ImClient,
) : AutoCloseable {
    private val lock = Any()
    private val observedMessageEvents = mutableListOf<E2eObservedMessageEvent>()
    private var bound: BoundProjection? = null
    private var closed = false

    /**
     * 在成功的 AUTH 回调内同步安装投影。
     *
     * [ImClient] 在进入 SYNCHRONIZING 之前调用该回调，因此 EventProcessor 的
     * 不可变 dataset 与 cursor 绑定会在第一个 SYNC_REQUEST 被接纳之前就位。
     * 对同一 dataset 的重连 AUTH 是幂等的；dataset 变化时必须像生产环境
     * 替换其 dataset 作用域的缓存图一样，替换整个测试会话。
     */
    fun bindDataset(datasetId: String) {
        SyncDatasetIdPolicy.requireValid(datasetId)
        synchronized(lock) {
            check(!closed) { "E2E event projection is closed" }
            bound?.let { current ->
                check(current.datasetId == datasetId) {
                    "E2E event projection cannot cross server datasets"
                }
                return
            }

            val cache = FakeLocalCache(initialDatasetId = null)
            val syncState = cache.bindSyncDataset(datasetId)
            check(syncState.datasetId == datasetId && syncState.cursor == 0L) {
                "E2E event projection did not bind a fresh authoritative dataset"
            }
            val processor = EventProcessor(
                imClient = client,
                localCache = cache,
                // 与 ImClient.packets 不同，这个边界还会观察到 SYNC_BATCH 内携带的消息。
                // 它运行在缓存插入之后、cursor 提交之前，因此即使缓存 upsert 是幂等的，
                // 重放的重复消息仍然可见。
                durableMessageSink = { eventId, message ->
                    synchronized(lock) {
                        observedMessageEvents += E2eObservedMessageEvent(eventId, message)
                    }
                },
            )
            try {
                processor.start()
            } catch (failure: Throwable) {
                releaseAfterFailedBind(processor, cache, failure)
            }
            bound = BoundProjection(datasetId, processor, cache)
        }
    }

    /** 供真实连接收敛测试使用的窄只读视图。 */
    fun conversation(chatId: String): Conversation? = readCache { cache ->
        cache.getConversations().firstOrNull { conversation -> conversation.chatId == chatId }
    }

    /** 消息身份在投递、编辑、重放与撤回之间保持稳定。 */
    fun message(chatId: String, serverSeq: Long): Message? =
        messages(chatId).firstOrNull { message -> message.serverSeq == serverSeq }

    /** 当前有界本地历史，用于区分客户端收敛与权威读取。 */
    fun messages(chatId: String): List<Message> = readCache { cache ->
        cache.getMessages(chatId, LocalCache.MAX_MESSAGE_READ_LIMIT)
    }

    /** 每次持久消息投影尝试，包括重放页面内携带的消息。 */
    fun observedMessages(): List<E2eObservedMessageEvent> = synchronized(lock) {
        check(!closed) { "E2E event projection is closed" }
        observedMessageEvents.toList()
    }

    /** 仅当对应事件已进入本地投影之后，cursor 才会推进。 */
    fun syncCursor(): Long = readCache { cache ->
        requireNotNull(cache.getSyncState()) { "E2E event projection has no bound sync state" }.cursor
    }

    override fun close() {
        val projection = synchronized(lock) {
            if (closed) return
            closed = true
            bound.also { bound = null }
        } ?: return

        var failure: Throwable? = null
        try {
            projection.processor.stop()
        } catch (stopFailure: Throwable) {
            failure = stopFailure
        }
        try {
            projection.cache.close()
        } catch (cacheFailure: Throwable) {
            val primary = failure
            if (primary == null) failure = cacheFailure else primary.addSuppressed(cacheFailure)
        }
        failure?.let { throw it }
    }

    private data class BoundProjection(
        val datasetId: String,
        val processor: EventProcessor,
        val cache: FakeLocalCache,
    )

    private fun <T> readCache(block: (FakeLocalCache) -> T): T = synchronized(lock) {
        check(!closed) { "E2E event projection is closed" }
        block(checkNotNull(bound) { "E2E event projection is not bound" }.cache)
    }

    private fun releaseAfterFailedBind(
        processor: EventProcessor,
        cache: FakeLocalCache,
        failure: Throwable,
    ): Nothing {
        runCatching(processor::stop).exceptionOrNull()?.let(failure::addSuppressed)
        runCatching(cache::close).exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

internal data class E2eObservedMessageEvent(
    val eventId: Long,
    val message: Message,
)

internal fun ImClient.installE2eEventProjection(): E2eEventProjection = E2eEventProjection(this)

internal fun ImClient.installE2eEventProjection(datasetId: String): E2eEventProjection =
    E2eEventProjection(this).also { it.bindDataset(datasetId) }
