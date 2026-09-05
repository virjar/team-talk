package com.virjar.tk.shared.testkit

import com.virjar.tk.protocol.model.Message

/**
 * FakeLocalCache 历史页应用前的确定性形状校验：chat 一致、非空 id、正 seq、无重复。
 */
internal fun requireFakeHistoryPageShape(messages: List<Message>, chatId: String) {
    val clientMsgIds = HashSet<String>(messages.size)
    val serverSeqs = HashSet<Long>(messages.size)
    messages.forEach { message ->
        require(message.chatId == chatId) {
            "history page contains another chat: ${message.chatId}"
        }
        require(message.clientMsgId.isNotBlank()) {
            "history page contains a blank clientMsgId"
        }
        require(message.serverSeq > 0L) {
            "history page contains a non-positive serverSeq"
        }
        require(clientMsgIds.add(message.clientMsgId)) {
            "history page contains duplicate clientMsgId"
        }
        require(serverSeqs.add(message.serverSeq)) {
            "history page contains duplicate serverSeq"
        }
    }
}
