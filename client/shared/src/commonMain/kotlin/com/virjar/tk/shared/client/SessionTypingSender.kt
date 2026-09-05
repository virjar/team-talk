package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * 对一个已认证会话拥有的正在输入信号执行 best-effort 准入。
 *
 * 把这条仅 transport 的路径保持在 [ClientSession] 之外，使其刻意更弱的投递契约可见：它从不创建
 * 可靠发件箱记录，生命周期竞争会直接拒绝它。
 */
internal fun trySendSessionTyping(
    chatId: String,
    ownerUid: String,
    lifecycle: SessionLifecycleGate,
    imClient: ImClient,
    transportOwnerGeneration: Long,
    outboundLease: SessionOutboundLease,
): Boolean {
    if (
        chatId.isBlank() ||
        chatId.length > MessageBodyPolicy.MAX_CHAT_ID_LENGTH ||
        '\u0000' in chatId ||
        !lifecycle.isBusinessActive()
    ) {
        return false
    }
    val message = Message(
        chatId = chatId,
        clientMsgId = UUID.randomUUID().toString(),
        senderUid = ownerUid,
        messageType = MessageType.TYPING.code,
        timestamp = System.currentTimeMillis(),
    )
    var enteredBusinessBlock = false
    return try {
        lifecycle.whileBusinessActive {
            enteredBusinessBlock = true
            if (
                imClient.state.value != ConnectionState.AUTHENTICATED ||
                imClient.currentTransportOwnerGeneration != transportOwnerGeneration ||
                !outboundLease.isActive()
            ) {
                false
            } else {
                imClient.sendSessionOwned(
                    expectedOwnerGeneration = transportOwnerGeneration,
                    sessionLease = outboundLease,
                    proto = message,
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalStateException) {
        // 只有进入生命周期块的失败才是预期的 check-then-use 竞争。来自消息校验或 transport
        // 代码的任何异常仍然是编程失败。
        if (enteredBusinessBlock) throw failure
        false
    }
}
