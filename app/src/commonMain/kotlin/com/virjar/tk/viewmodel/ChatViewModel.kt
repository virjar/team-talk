package com.virjar.tk.viewmodel

import com.virjar.tk.AppError
import com.virjar.tk.client.EventProcessor
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.MessagePager
import com.virjar.tk.model.Message
import com.virjar.tk.repository.MessageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

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
    private val imClient: ImClient,
    private val localCache: LocalCache,
    private val messageRepo: MessageRepository,
    eventProcessor: EventProcessor? = null,
    private val myUid: String = "",
) : BaseViewModel() {
    private val logger = LoggerFactory.getLogger("ChatViewModel")

    // 消息分页器（LocalCache 内部 LRU 管理，超出 MAX_ACTIVE_CHATS 自动 evict）
    private val pager: MessagePager = localCache.pager(chatId)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 当前正在输入的用户 uid，null 表示无人输入 */
    private val _typingUid = MutableStateFlow<String?>(null)
    val typingUid: StateFlow<String?> = _typingUid.asStateFlow()

    private var typingJob: Job? = null

    init {
        // 监听本地消息窗口变化（pager 创建时已从 DB 加载最近窗口）
        scope.launch {
            pager.messages.collect { _messages.value = it }
        }

        // 从服务端拉取最新消息（写入 LocalCache 后 pager 自动看到）
        // loadHistory 成功后会触发 markRead（确保 _messages 已填充最新 seq）
        loadHistory(markReadAfter = true)

        // 监听 typing 事件
        if (eventProcessor != null) {
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
    }

    /** 从服务端拉取最新消息（同步到本地 DB，pager 自动更新）。 */
    fun loadHistory(markReadAfter: Boolean = false) {
        scope.launch {
            try {
                _loading.value = true
                messageRepo.getHistory(chatId, fromSeq = 0).getOrThrow()
                if (markReadAfter) markRead()
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (e: Exception) {
                setError("加载消息失败: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    fun sendMessage(message: Message) {
        // 乐观更新：立即显示为 sending
        val sending = message.copy(sendStatus = Message.SEND_STATUS_SENDING)
        localCache.insertMessage(sending)
        scope.launch {
            try {
                val ack = imClient.sendAndWaitAck(sending)
                if (ack.code == 0) {
                    localCache.updateMessage(sending.chatId, sending.clientMsgId, ack.serverSeq)
                    // 自己发的消息是最新的，推进 readSeq 到它 + 本地清零未读
                    // （自己发消息不会触发 CONVERSATION_UPDATED 通知，必须本地同步）
                    markRead(ack.serverSeq)
                } else {
                    localCache.updateMessageStatus(sending.chatId, sending.clientMsgId, Message.SEND_STATUS_FAILED)
                    setError("发送失败: ${ack.reason}")
                }
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

    /**
     * 标记已读。
     * @param seq 显式指定的 readSeq；null 时取当前消息列表的最新 seq。
     *
     * 关键：服务端 markRead 成功后**立即本地清零 unreadCount**，不依赖
     * CONVERSATION_UPDATED 通知回环——自己发消息不会触发该通知。
     */
    fun markRead(seq: Long? = null) {
        scope.launch {
            try {
                val readSeq = seq ?: _messages.value.firstOrNull()?.serverSeq ?: return@launch
                messageRepo.markRead(chatId, readSeq).getOrThrow()
                // 本地立即清零未读数，红点即时消失
                localCache.markConversationRead(chatId, readSeq)
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (e: Exception) {
                logger.warn("markRead failed", e)
            }
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

    fun editMessage(message: Message) {
        scope.launch {
            // 乐观更新：立即更新本地缓存，用户马上看到效果
            val optimistic = message.copy(flags = message.flags or Message.FLAG_EDITED)
            localCache.insertMessage(optimistic)
            try {
                messageRepo.editMessage(message).getOrThrow()
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (e: Exception) {
                setError("编辑失败: ${e.message}")
            }
        }
    }

    /**
     * 认证失效：断开连接触发 UI 回到登录页（停而非重试）。
     */
    private fun handleAuthExpired() {
        setError("认证失效，请重新登录")
        imClient.disconnect()
    }

    /** 释放内存窗口（Phase C LRU 治理）。 */
    override fun destroy() {
        localCache.onChatInactive(chatId)
        super.destroy()
    }
}
