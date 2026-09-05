package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.ProtoCodec

/** 重建被破坏性 reset 的消息投影的有界持久可靠发件箱部分。 */
internal class LocalMessageProjectionResetStore(
    private val queries: AppDatabaseQueries,
) {
    /** 调用方持有 LocalCache 的状态锁与外层 reset 事务。 */
    fun rebuildOutgoingProjection() {
        queries.selectAllOutgoingMessages().executeAsList()
            .filter { it.state != OutgoingMessageState.SUCCESS.code }
            .forEach { row ->
                persist(row.toProjectionMessage())
                if (row.state == OutgoingMessageState.TERMINAL_FAILED.code) {
                    queries.updateMessageTerminalFailure(
                        requireNotNull(row.failure_code) {
                            "Terminal outgoing receipt has no stable failure code"
                        },
                        row.chat_id,
                        row.client_msg_id,
                    )
                }
            }
    }

    private fun persist(message: Message) {
        check(message.serverSeq == 0L) { "Projection reset can rebuild only optimistic messages" }
        queries.insertMessage(
            message.chatId,
            message.clientMsgId,
            message.serverSeq,
            message.senderUid,
            message.messageType.toLong(),
            message.timestamp,
            message.flags.toLong(),
            message.body?.let(ProtoCodec::encode),
            message.sendStatus.toLong(),
        )
    }
}
