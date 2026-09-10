package com.virjar.tk.app.navigation.feature.document

import androidx.compose.runtime.Stable
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.shared.client.ClientSession
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * 从文档工作台把当前文档以 OFFICE_REF 引用消息分享到任一会话。
 *
 * 发送接入与会话气泡相同的持久 outbox：admit 成功即乐观上屏并可靠补发；
 * 返回 false 表示本地队列未准入（会话退役等），调用方给出可重试提示。
 */
@Stable
internal class DocumentShareToChatAction(private val session: ClientSession) {

    fun conversations(): Flow<List<Conversation>> = session.localCache.observeConversations()

    fun myUid(): String = session.userSession.uid

    fun send(chatId: String, ref: OfficeRefBody): Boolean {
        val message = Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = session.userSession.uid,
            messageType = MessageType.OFFICE_REF.code,
            timestamp = System.currentTimeMillis(),
            body = ref,
        ).copy(sendStatus = Message.SEND_STATUS_SENDING)
        return session.localMutations.enqueueOutgoing(message) {
            // 持久队列自身会把乐观行标记为失败；这里无需额外状态。
        }
    }
}
