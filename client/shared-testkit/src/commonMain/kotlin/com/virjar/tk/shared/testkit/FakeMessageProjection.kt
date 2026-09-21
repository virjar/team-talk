package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.platform.*
import com.virjar.tk.protocol.model.Message

internal fun upsertFakeMessageProjection(
    messagesLock: PlatformLock,
    messagesByChat: MutableMap<String, MutableList<Message>>,
    message: Message,
    onChatChanged: (String) -> Unit,
) = synchronized(messagesLock) {
    val messages = messagesByChat.getOrPut(message.chatId) { mutableListOf() }
    val index = messages.indexOfFirst { it.clientMsgId == message.clientMsgId }
    if (index >= 0) {
        if (messages[index].serverSeq > 0L && message.serverSeq == 0L) return@synchronized
        messages[index] = message
    } else {
        messages.add(message)
    }
    messages.sortWith(fakeMessageOrder)
    onChatChanged(message.chatId)
}

internal fun updateFakeMessageProjectionStatus(
    messagesLock: PlatformLock,
    messagesByChat: MutableMap<String, MutableList<Message>>,
    message: Message,
    sendStatus: Int,
    onChatChanged: (String) -> Unit,
) = updateOptimisticFakeMessage(messagesLock, messagesByChat, message, onChatChanged) {
    copy(sendStatus = sendStatus)
}

internal fun completeFakeMessageProjection(
    messagesLock: PlatformLock,
    messagesByChat: MutableMap<String, MutableList<Message>>,
    message: Message,
    serverSeq: Long,
    onChatChanged: (String) -> Unit,
) = updateOptimisticFakeMessage(messagesLock, messagesByChat, message, onChatChanged) {
    copy(serverSeq = serverSeq, sendStatus = Message.SEND_STATUS_SENT)
}

internal fun markFakeAuthoritativeMessageSent(
    messagesLock: PlatformLock,
    messagesByChat: MutableMap<String, MutableList<Message>>,
    message: Message,
    onChatChanged: (String) -> Unit,
) = synchronized(messagesLock) {
    val messages = messagesByChat[message.chatId] ?: return@synchronized
    val index = messages.indexOfFirst { it.clientMsgId == message.clientMsgId }
    if (index >= 0 && messages[index].serverSeq > 0L) {
        messages[index] = messages[index].copy(sendStatus = Message.SEND_STATUS_SENT)
        onChatChanged(message.chatId)
    }
}

private fun updateOptimisticFakeMessage(
    messagesLock: PlatformLock,
    messagesByChat: MutableMap<String, MutableList<Message>>,
    message: Message,
    onChatChanged: (String) -> Unit,
    transform: Message.() -> Message,
) = synchronized(messagesLock) {
    val messages = messagesByChat[message.chatId] ?: return@synchronized
    val index = messages.indexOfFirst { it.clientMsgId == message.clientMsgId }
    if (index >= 0 && messages[index].serverSeq == 0L) {
        messages[index] = messages[index].transform()
        onChatChanged(message.chatId)
    }
}
