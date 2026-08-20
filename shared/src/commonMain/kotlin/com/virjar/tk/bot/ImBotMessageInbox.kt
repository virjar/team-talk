package com.virjar.tk.bot

import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.PendingBotMessage
import com.virjar.tk.model.Message
import kotlinx.coroutines.channels.Channel

/**
 * ImBot 的账号缓存打开策略。
 *
 * 调用方必须明确选择持久缓存或测试缓存；[open] 只会在认证成功并取得服务端 uid 后调用，
 * 返回的缓存所有权随即移交给 ClientSession，并由 ImBot.shutdown 级联关闭。
 */
fun interface ImBotCacheOwner {
    fun open(uid: String): LocalCache
}

/**
 * ImBot 的单消费者可靠收件箱。
 *
 * 消息主体落在账号 LocalCache 的磁盘表；进程内只用 CONFLATED wake-up，因此初始 replay
 * 不依赖消费者启动时序、不会形成内存 backlog。eventId 与 `(chatId, serverSeq)` 都是
 * INSERT OR IGNORE 幂等边界。
 */
class ImBotMessageInbox {
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val stateLock = Any()
    private var localCache: LocalCache? = null
    private var closed = false

    /** Cache lifecycle remains owned by ClientSession; inbox only borrows it until [close]. */
    internal fun bind(cache: LocalCache) {
        synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            check(localCache == null) { "ImBot inbox is already bound" }
            localCache = cache
        }
        // Wake a consumer which started before authentication/cache creation.
        wakeUp.trySend(Unit)
    }

    internal fun publish(eventId: Long, message: Message) {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        cache.enqueueBotMessage(eventId, message)
        wakeUp.trySend(Unit)
    }

    /** 读取但不删除最早一条持久消息；业务接受后必须显式 [ack]。 */
    suspend fun receivePending(): PendingBotMessage =
        checkNotNull(receivePendingOrNull()) { "ImBot inbox is closed" }

    /** inbox 关闭时返回 null；未绑定或暂时为空时等待 CONFLATED wake-up。 */
    suspend fun receivePendingOrNull(): PendingBotMessage? {
        while (true) {
            val state = synchronized(stateLock) { localCache to closed }
            if (state.second) return null
            state.first?.peekBotMessage()?.let { return it }
            if (wakeUp.receiveCatching().isClosed) return null
        }
    }

    /** 确认业务已经接受该 delivery；崩溃前未调用则重启后会再次收到。 */
    fun ack(eventId: Long) {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        cache.deleteBotMessage(eventId)
        // There may already be a following durable row.
        wakeUp.trySend(Unit)
    }

    /** tt-agent recent/history 的磁盘事实源；不依赖进程内 ring。 */
    fun recentMessages(chatId: String?, afterSeq: Long, limit: Int): List<Message> {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        return cache.getRecentMessages(chatId, afterSeq, limit)
    }

    /**
     * at-most-once 便利读取：在返回前自动 ack。需要跨业务处理重试时使用
     * [receivePending] + [ack]，不要调用此方法。
     */
    suspend fun receive(): Message {
        val pending = receivePending()
        ack(pending.eventId)
        return pending.message
    }

    /** at-most-once 便利读取；inbox 关闭时返回 null。 */
    suspend fun receiveOrNull(): Message? {
        val pending = receivePendingOrNull() ?: return null
        ack(pending.eventId)
        return pending.message
    }

    internal fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            localCache = null
        }
        wakeUp.close()
    }
}
