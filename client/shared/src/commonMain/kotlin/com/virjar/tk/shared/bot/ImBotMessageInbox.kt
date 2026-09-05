package com.virjar.tk.shared.bot

import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.MAX_BOT_DELIVERY_HISTORY_PAGE_SIZE
import com.virjar.tk.shared.client.PendingBotMessage
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.channels.Channel

/**
 * ImBot 的账号缓存打开策略。
 *
 * 调用方必须明确选择持久缓存或测试缓存；[open] 只会在认证成功并取得服务端 uid 后调用，
 * 返回的缓存所有权随即移交给 ClientSession，并由 ImBot.shutdown 级联关闭。
 */
fun interface ImBotCacheOwner {
    fun open(deploymentIdentity: DeploymentIdentity, datasetId: String, uid: String): LocalCache
}

/**
 * ImBot 的单消费者可靠收件箱。
 *
 * 消息主体落在账号 LocalCache 的磁盘表；进程内只用 CONFLATED wake-up，因此初始 replay
 * 不依赖消费者启动时序、不会形成内存 backlog。eventId 是 INSERT OR IGNORE 的重放幂等
 * 边界；同一 `(chatId, serverSeq)` 的创建、编辑和撤回会作为不同事件完整保留。
 */
class ImBotMessageInbox {
    private class ChatGate {
        val monitor = Any()
        var borrowers: Int = 0
    }

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val stateLock = Any()
    private val chatGates = mutableMapOf<String, ChatGate>()
    private var localCache: LocalCache? = null
    private var closed = false

    /** 缓存生命周期仍归 ClientSession 所有；inbox 只是借用它直到 [close]。 */
    internal fun bind(cache: LocalCache) {
        synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            check(localCache == null) { "ImBot inbox is already bound" }
            localCache = cache
        }
        // 唤醒在认证/缓存创建之前就已启动的消费者。
        wakeUp.trySend(Unit)
    }

    internal fun publish(eventId: Long, message: Message) {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        withChatGate(message.chatId) {
            cache.enqueueBotMessage(eventId, message)
        }
        wakeUp.trySend(Unit)
    }

    /**
     * 仅供 agent 使用的原子消费边界。候选发现被刻意设计为在进入每会话门禁后重新校验；
     * 赢得该门禁的 CHAT_DELETED 会在 [consume] 能够 ack 或通知等待者之前删除持久行。
     * 输掉竞争的墓碑只会在该通知之后才执行。
     */
    internal suspend fun consumePendingForAgent(consume: (PendingBotMessage) -> Unit): Boolean {
        while (true) {
            val state = synchronized(stateLock) { localCache to closed }
            if (state.second) return false
            val cache = state.first
            if (cache == null) {
                if (wakeUp.receiveCatching().isClosed) return false
                continue
            }
            val candidate = cache.peekBotMessage()
            if (candidate != null) {
                val consumed = withChatGate(candidate.message.chatId) {
                    val current = cache.peekBotMessage()
                    if (current?.eventId != candidate.eventId) {
                        false
                    } else {
                        cache.ackBotMessage(current.eventId, System.currentTimeMillis())
                        consume(current)
                        true
                    }
                }
                if (consumed) {
                    // 后面可能已经跟着一条持久行。
                    wakeUp.trySend(Unit)
                    return true
                }
                // 候选输给了同会话的墓碑（或另一个消费者）。不做等待直接发现
                // 新的全局队首；其他会话仍然可以独立使用。
                continue
            }
            if (wakeUp.receiveCatching().isClosed) return false
        }
    }

    /** 在 LocalCache 的完整权威墓碑操作全程持有同一把每会话门禁。 */
    internal fun applyChatTombstone(chatId: String, tombstone: () -> Unit) {
        synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        withChatGate(chatId, tombstone)
        // 移除全局待处理队首后，可能暴露出属于其他会话的行。
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
        cache.ackBotMessage(eventId, System.currentTimeMillis())
        // 后面可能已经跟着一条持久行。
        wakeUp.trySend(Unit)
    }

    /**
     * 按全局 event-id 顺序保留的投递历史。零表示从当前保留窗口的起点开始；
     * 早于持久保留下限的正数游标会被拒绝。
     */
    fun deliveries(afterEventId: Long, chatId: String?, limit: Int): List<PendingBotMessage> {
        require(afterEventId >= 0L) { "afterEventId must be non-negative" }
        require(limit in 1..MAX_BOT_DELIVERY_HISTORY_PAGE_SIZE) {
            "limit must be between 1 and $MAX_BOT_DELIVERY_HISTORY_PAGE_SIZE"
        }
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        return cache.listBotMessageDeliveries(afterEventId, chatId, limit)
    }

    /** 供不带游标的 recv-wait 使用的快照，使其只观察未来的投递。 */
    fun maxEventId(): Long {
        val cache = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            checkNotNull(localCache) { "ImBot inbox is not bound" }
        }
        return cache.maxBotMessageEventId()
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

    private fun <T> withChatGate(chatId: String, block: () -> T): T {
        require(chatId.isNotBlank()) { "chatId must not be blank" }
        val gate = synchronized(stateLock) {
            check(!closed) { "ImBot inbox is closed" }
            chatGates.getOrPut(chatId, ::ChatGate).also { it.borrowers += 1 }
        }
        return try {
            synchronized(gate.monitor) { block() }
        } finally {
            synchronized(stateLock) {
                check(gate.borrowers > 0) { "ImBot inbox chat gate borrower underflow" }
                gate.borrowers -= 1
                if (gate.borrowers == 0 && chatGates[chatId] === gate) {
                    chatGates.remove(chatId)
                }
            }
        }
    }
}
