package com.virjar.tk.tcp.agent

import com.virjar.tk.protocol.payload.HistoryLoadEndPayload
import com.virjar.tk.protocol.payload.HistoryLoadPayload
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.service.MessageService
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.tcp.ImAgent
import java.lang.ref.WeakReference

/**
 * 历史消息加载处理器：处理 HISTORY_LOAD 请求。
 *
 * 职责：权限校验、消息查询、批量推送、结束标记。
 */
object HistoryDispatcher {
    private lateinit var messageService: MessageService
    private lateinit var channelStore: ChannelStore

    fun init(messageService: MessageService, channelStore: ChannelStore) {
        this.messageService = messageService
        this.channelStore = channelStore
    }

    fun handleHistoryLoad(agent: ImAgent, payload: HistoryLoadPayload) {
        val channelId = payload.channelId
        val beforeSeq = payload.beforeSeq
        val limit = payload.limit
        val uid = agent.uid

        agent.recorder.record { "[HISTORY_LOAD] channelId=$channelId beforeSeq=$beforeSeq limit=$limit uid=$uid" }

        // 权限校验：内存查找，不阻塞
        if (!channelStore.isMember(channelId, uid)) {
            agent.recorder.record { "[HISTORY_LOAD] denied: uid=$uid not member of $channelId" }
            agent.write(HistoryLoadEndPayload(channelId, beforeSeq, false))
            agent.flush()
            return
        }

        val messages: List<Message> = if (beforeSeq <= 0) {
            messageService.getLatestMessages(channelId, limit)
        } else {
            messageService.getMessagesBeforeSeq(channelId, beforeSeq, limit)
        }

        for (msg in messages) {
            agent.write(msg)
        }

        val hasMore = messages.size >= limit
        agent.write(HistoryLoadEndPayload(channelId, beforeSeq, hasMore))
        agent.flush()

        agent.recorder.record { "[HISTORY_LOAD] sent ${messages.size} messages for channel=$channelId hasMore=$hasMore" }
    }
}
