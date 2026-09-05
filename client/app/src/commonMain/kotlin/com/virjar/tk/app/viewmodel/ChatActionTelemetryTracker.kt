package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.client.OutgoingMessageState
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink

/**
 * 在不导出它们标识符的情况下，把 chat UI 尝试与持久本地事实关联起来。
 *
 * 发送候选只是驻留消息投影的提示。[completeSendProbe] 只有在调用方检查了
 * SQLite 发出回执之后才发出终止结果。读取完成由 SQLite 支撑的会话投影
 * 达到请求的水位线直接证明。
 */
internal class ChatActionTelemetryTracker(
    private val sink: ClientUiTelemetrySink,
) {
    private val lock = Any()
    private val pendingSendIds = mutableSetOf<String>()
    private val sendProbesInFlight = mutableSetOf<String>()
    private var pendingReadSeq: Long? = null

    fun startSend(clientMsgId: String) {
        require(clientMsgId.isNotBlank()) { "Send telemetry identity must not be blank" }
        val started = synchronized(lock) { pendingSendIds.add(clientMsgId) }
        if (started) record(ClientUiAction.SEND_MESSAGE, ClientActionOutcome.STARTED)
    }

    fun failSend(clientMsgId: String) {
        val failed = synchronized(lock) {
            sendProbesInFlight.remove(clientMsgId)
            pendingSendIds.remove(clientMsgId)
        }
        if (failed) record(ClientUiAction.SEND_MESSAGE, ClientActionOutcome.FAILED)
    }

    /** 返回需要在 UI 线程之外做持久 outbox 回执检查的标识符。 */
    fun terminalSendCandidates(messages: List<Message>): List<String> = synchronized(lock) {
        messages.asSequence()
            .filter { message ->
                message.clientMsgId in pendingSendIds &&
                    message.clientMsgId !in sendProbesInFlight &&
                    message.hasPotentialSendTerminal()
            }
            .map(Message::clientMsgId)
            .distinct()
            .onEach(sendProbesInFlight::add)
            .toList()
    }

    fun completeSendProbe(clientMsgId: String, state: OutgoingMessageState?) {
        val outcome = synchronized(lock) {
            sendProbesInFlight.remove(clientMsgId)
            if (clientMsgId !in pendingSendIds) return@synchronized null
            when (state) {
                OutgoingMessageState.SUCCESS -> {
                    pendingSendIds.remove(clientMsgId)
                    ClientActionOutcome.SUCCEEDED
                }
                OutgoingMessageState.TERMINAL_FAILED -> {
                    pendingSendIds.remove(clientMsgId)
                    ClientActionOutcome.FAILED
                }
                OutgoingMessageState.PENDING,
                OutgoingMessageState.IN_FLIGHT,
                OutgoingMessageState.RETRY_WAIT,
                null,
                -> null
            }
        }
        outcome?.let { record(ClientUiAction.SEND_MESSAGE, it) }
    }

    fun startMarkRead(readSeq: Long) {
        require(readSeq > 0L) { "Read telemetry waterline must be positive" }
        val started = synchronized(lock) {
            val previous = pendingReadSeq
            pendingReadSeq = maxOf(previous ?: 0L, readSeq)
            previous == null
        }
        if (started) record(ClientUiAction.MARK_READ, ClientActionOutcome.STARTED)
    }

    /**
     * 合并的读取命令只保留最近的失败回调。一条更旧的失败命令绝不能
     * 终止一条更新、更高水位线、仍然排在它后面的命令。
     */
    fun failMarkRead(failedReadSeq: Long) {
        val failed = synchronized(lock) {
            val pending = pendingReadSeq
            if (pending == null || pending > failedReadSeq) false else {
                pendingReadSeq = null
                true
            }
        }
        if (failed) record(ClientUiAction.MARK_READ, ClientActionOutcome.FAILED)
    }

    fun observeReadSeq(committedReadSeq: Long) {
        val succeeded = synchronized(lock) {
            val pending = pendingReadSeq
            if (pending == null || committedReadSeq < pending) false else {
                pendingReadSeq = null
                true
            }
        }
        if (succeeded) record(ClientUiAction.MARK_READ, ClientActionOutcome.SUCCEEDED)
    }

    private fun record(action: ClientUiAction, outcome: ClientActionOutcome) {
        sink.recordAction(ClientUiPage.CHAT, action, outcome)
    }
}

private fun Message.hasPotentialSendTerminal(): Boolean =
    (sendStatus == Message.SEND_STATUS_SENT && serverSeq > 0L) ||
        sendStatus == Message.SEND_STATUS_FAILED
