package com.virjar.tk.viewmodel

import com.virjar.tk.AppError
import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.MessagePager
import com.virjar.tk.model.Message
import com.virjar.tk.repository.MessageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 聊天 ViewModel。
 * 管理消息列表、发送消息、加载历史。
 *
 * 内存治理（Phase C）：
 * - 通过 [LocalCache.pager] 观察内存窗口中的消息（默认最近 100 条）
 * - [loadOlder] 向上翻页加载本地更老消息
 * - [destroy] 时通知 LocalCache 释放该聊天的内存窗口
 */
class ChatViewModel(
    private val chatId: String,
    private val localCache: LocalCache,
    private val messageRepo: MessageRepository,
    eventProcessor: EventProcessor,
    private val myUid: String = "",
    /** 发送队列（断线排队重连补发）；null=回退旧直发路径（测试桩） */
    private val sendQueue: com.virjar.tk.client.SendQueue? = null,
) : BaseViewModel() {

    // 消息分页器（LocalCache 内部 LRU 管理，超出 MAX_ACTIVE_CHATS 自动 evict）
    private val pager: MessagePager = localCache.pager(chatId)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    private val _remoteHasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = combine(pager.hasMore, _remoteHasMore) { local, remote ->
        local || remote
    }.stateIn(scope, SharingStarted.Eagerly, true)

    /** 当前正在输入的用户 uid，null 表示无人输入 */
    private val _typingUid = MutableStateFlow<String?>(null)
    val typingUid: StateFlow<String?> = _typingUid.asStateFlow()

    private var typingJob: Job? = null
    private val readReceipts = ReadReceiptQueue(scope, ::syncReadWatermark)

    init {
        // 监听本地消息窗口变化（pager 创建时已从 DB 加载最近窗口）
        scope.launch {
            pager.messages.collect { _messages.value = it }
        }

        // 从服务端拉取最新消息（写入 LocalCache 后 pager 自动看到）。是否已读只由
        // 实际可见的 ChatPanel 决定，后台 ViewModel 不得因同步历史而消费红点。
        loadHistory()

        // 监听 typing 事件
        scope.launch {
            eventProcessor.typingEvents.collect { (cid, uid) ->
                if (cid == chatId && uid != myUid) {
                    _typingUid.value = uid
                    // 3 秒后自动清除
                    typingJob?.cancel()
                    typingJob = scope.launch {
                        delay(3000)
                        _typingUid.value = null
                    }
                }
            }
        }
    }

    /** 从服务端拉取最新消息（同步到本地 DB，pager 自动更新）。 */
    fun loadHistory() {
        scope.launch {
            try {
                _loading.value = true
                val latest = messageRepo.getHistory(chatId, fromSeq = 0, limit = HISTORY_PAGE_SIZE).getOrThrow()
                _remoteHasMore.value = latest.size == HISTORY_PAGE_SIZE
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (e: Exception) {
                setError("加载消息失败: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Load the next older page. Local persisted rows are consumed first; once exhausted, request
     * the server page immediately before the oldest confirmed sequence currently visible.
     */
    fun loadOlder() {
        if (_loading.value || _loadingOlder.value || !hasMore.value) return
        scope.launch {
            _loadingOlder.value = true
            try {
                if (pager.hasMore.value) {
                    pager.loadMore(HISTORY_PAGE_SIZE)
                    return@launch
                }
                if (!_remoteHasMore.value) return@launch
                // MessageWindow atomically anchors the visible list to the newest server page, so
                // its minimum is the floor of that proven response chain. A stale pre-sync local
                // tail is deliberately not present here; legal sequence holes inside a page are.
                val oldestSeq = _messages.value.asSequence()
                    .map(Message::serverSeq)
                    .filter { it > 0L }
                    .minOrNull()
                    ?: 0L
                if (oldestSeq <= 1L) {
                    _remoteHasMore.value = false
                    return@launch
                }
                val older = messageRepo.getHistory(
                    chatId = chatId,
                    // RocksDB's backwards cursor is inclusive; step left once so the boundary
                    // message is not returned on every page.
                    fromSeq = oldestSeq - 1L,
                    limit = HISTORY_PAGE_SIZE,
                ).getOrThrow()
                _remoteHasMore.value = older.size == HISTORY_PAGE_SIZE
                // MessageRepository applies the authoritative response to MessageWindow as one
                // atomic page, including legal gaps, so no secondary SQLite append is required.
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (e: Exception) {
                setError("加载更早消息失败: ${e.message}")
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    /**
     * 媒体上传占位：先本地插入 UPLOADING 消息（气泡立即渲染上传动画），
     * 上传完成后调 [sendMessage] 以同 clientMsgId 覆盖（upsert）为真实消息。
     */
    fun insertUploadingPlaceholder(message: Message) {
        localCache.insertMessage(message)
    }

    /** 更新上传进度（驱动气泡进度动画；纯 UI 状态不落库）。 */
    fun updateUploadProgress(chatId: String, clientMsgId: String, progress: Float) {
        localCache.updateMessageInMemory(chatId, clientMsgId) { it.copy(uploadProgress = progress) }
    }

    fun sendMessage(message: Message) {
        // 乐观更新：立即显示为 sending
        val sending = message.copy(sendStatus = Message.SEND_STATUS_SENDING)
        localCache.insertMessage(sending)
        // 发送队列路径：断线排队（QUEUED）→ 重连补发，状态机由队列回调推进
        val queue = sendQueue
        if (queue != null) {
            queue.enqueue(sending)
            return
        }
        scope.launch {
            try {
                val ack = messageRepo.send(sending).getOrThrow()
                if (ack.code == 0) {
                    localCache.updateMessage(sending.chatId, sending.clientMsgId, ack.serverSeq)
                    // 自己发的消息是最新的，推进 readSeq 到它 + 本地清零未读
                    // （自己发消息不会触发 CONVERSATION_UPDATED 通知，必须本地同步）
                    markRead(ack.serverSeq)
                } else {
                    localCache.updateMessageStatus(sending.chatId, sending.clientMsgId, Message.SEND_STATUS_FAILED)
                    setError("发送失败: ${ack.reason}")
                }
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (e: Exception) {
                localCache.updateMessageStatus(sending.chatId, sending.clientMsgId, Message.SEND_STATUS_FAILED)
                setError("发送失败: ${e.message}")
            }
        }
    }

    /**
     * 供平台媒体工具（DesktopMediaHelper 等）调用，设置错误提示。
     */
    fun onError(msg: String) = setError(msg)

    /** 上传失败的占位消息标记为 FAILED（气泡显示失败态）。 */
    fun markUploadFailed(chatId: String, clientMsgId: String) {
        localCache.updateMessageStatus(chatId, clientMsgId, Message.SEND_STATUS_FAILED)
    }

    /**
     * 标记已读。
     * @param seq 显式指定的 readSeq；null 时取当前消息列表的最新 seq。
     *
     * 关键：请求由单一队列合并、串行提交；RPC 成功后才推进本地确认水位。这样既不会
     * 在网络失败时永久吞掉未读，也不会让消息突发产生并发 READ_SYNC 风暴。
     */
    fun markRead(seq: Long? = null) {
        val readSeq = seq ?: _messages.value.maxOfOrNull(Message::serverSeq) ?: return
        if (readSeq > 0L) readReceipts.request(readSeq)
    }

    private suspend fun syncReadWatermark(readSeq: Long): Boolean {
        return try {
            messageRepo.markRead(chatId, readSeq).getOrThrow()
            // Persist only after the RPC succeeds. Without a durable read outbox, optimistic
            // persistence would permanently hide server unread state after a failed request.
            localCache.markConversationRead(chatId, readSeq)
            true
        } catch (e: AppError.AuthExpired) {
            handleAuthExpired()
            false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.virjar.tk.util.AppLog.trace("ChatViewModel", "markRead failed: ${e.message}")
            false
        }
    }

    fun revokeMessage(serverSeq: Long) {
        scope.launch {
            try {
                messageRepo.revokeMessage(chatId, serverSeq).getOrThrow()
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (e: Exception) {
                setError("撤回失败: ${e.message}")
            }
        }
    }

    fun editMessage(message: Message, onResult: (Boolean) -> Unit = {}) {
        val previous = localCache.getMessages(message.chatId, limit = 200).firstOrNull {
            (message.serverSeq > 0 && it.serverSeq == message.serverSeq) ||
                it.clientMsgId == message.clientMsgId
        }
        scope.launch {
            // 乐观更新：立即更新本地缓存，用户马上看到效果
            val optimistic = message.copy(flags = message.flags or Message.FLAG_EDITED)
            localCache.insertMessage(optimistic)
            try {
                messageRepo.editMessage(message).getOrThrow()
                onResult(true)
            } catch (e: AppError.AuthExpired) {
                previous?.let(localCache::insertMessage)
                handleAuthExpired()
                onResult(false)
            } catch (e: Exception) {
                previous?.let(localCache::insertMessage)
                setError("编辑失败: ${e.message}")
                onResult(false)
            }
        }
    }

    /** 释放内存窗口（Phase C LRU 治理）。 */
    override fun destroy() {
        readReceipts.close()
        localCache.onChatInactive(chatId)
        super.destroy()
    }

    private companion object {
        const val HISTORY_PAGE_SIZE = 10
    }
}

/** Serializes read receipts and collapses every waiting burst to its highest sequence. */
internal class ReadReceiptQueue(
    scope: kotlinx.coroutines.CoroutineScope,
    private val synchronize: suspend (Long) -> Boolean,
) {
    private val requests = Channel<Long>(Channel.UNLIMITED)
    private val worker = scope.launch {
        var desired = 0L
        var confirmed = 0L
        for (first in requests) {
            desired = maxOf(desired, first)
            while (true) {
                val next = requests.tryReceive().getOrNull() ?: break
                desired = maxOf(desired, next)
            }
            if (desired <= confirmed) continue
            if (synchronize(desired)) confirmed = desired
        }
    }

    fun request(readSeq: Long) {
        if (readSeq > 0L) requests.trySend(readSeq)
    }

    fun close() {
        requests.close()
        worker.cancel()
    }
}
