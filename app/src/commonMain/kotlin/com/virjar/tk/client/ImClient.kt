package com.virjar.tk.client

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.HistoryLoadEndPayload
import com.virjar.tk.protocol.payload.HistoryLoadPayload
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.util.AppLog
import io.netty.channel.EventLoop
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// ── 回调接口 ──

interface ImStateListener {
    fun onConnectionStateChanged(state: ImClient.State)
    fun onMessageReceived(msg: Message)
    fun onSendAck(clientMsgNo: String, messageId: String, serverSeq: Long)
    fun onSendFailed(clientMsgNo: String)
    fun onCmdReceived(cmdType: String, payload: String)
    fun onPresenceReceived(uid: String, online: Boolean, lastSeenAt: Long) {}
    fun onTypingReceived(channelId: String, senderUid: String, action: Byte) {}
    fun onEditReceived(channelId: String, targetMessageId: String, newPayload: String, editedAt: Long) {}
    fun onMemberMuted(channelId: String, memberUid: String, operatorUid: String, duration: Long) {}
    fun onMemberUnmuted(channelId: String, memberUid: String, operatorUid: String) {}
    fun onMemberRoleChanged(channelId: String, memberUid: String, operatorUid: String, oldRole: Byte, newRole: Byte) {}
    fun onHistoryLoadEnd(channelId: String, beforeSeq: Long, hasMore: Boolean) {}
    fun onAuthFailed(code: Byte, reason: String) {}
}

// ── 发送队列 ──

data class PendingMessage(
    val clientMsgNo: String,
    val clientSeq: Long,
    val channelId: String,
    val channelType: ChannelType,
    val body: MessageBody,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

// ── ImClient ──

class ImClient(
    private val host: String,
    private val port: Int,
    private val uid: String,
    private val token: String,
    private val deviceId: String,
    private val stateListener: ImStateListener,
) : ConnectionListener {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    // 单线程 EventLoop
    private val workerGroup = NioEventLoopGroup(1)
    private val eventLoop: EventLoop = workerGroup.next()

    var state: State = State.DISCONNECTED
        private set

    private var currentConnection: TcpConnection? = null
    private var retryCount = 0
    private var destroyed = false
    private var connecting = false
    private var reconnectSchedule: ScheduledFuture<*>? = null

    // 发送队列（所有操作在 EventLoop 上，无需并发容器）
    private val sendQueue = ArrayDeque<PendingMessage>()
    private val pendingAcks = mutableMapOf<String, ((Result<SendAckPayload>) -> Unit)>()
    private val msgNoCounter = AtomicLong(0)
    private val clientSeqCounter = AtomicLong(0)

    // ── 公共 API ──

    fun connect() {
        doOnMainThread {
            if (destroyed || connecting) return@doOnMainThread
            connecting = true
            setState(State.CONNECTING)
            createAndConnect()
        }
    }

    fun disconnect() {
        doOnMainThread {
            destroyed = true
            reconnectSchedule?.cancel(false)
            reconnectSchedule = null
            currentConnection?.close()
            currentConnection = null
            // 失败所有 pending 的消息
            pendingAcks.forEach { (_, cb) -> cb.invoke(Result.failure(RuntimeException("Disconnected"))) }
            pendingAcks.clear()
            sendQueue.clear()
            setState(State.DISCONNECTED)
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        }
    }

    /**
     * 入队消息，body 是具体的 MessageBody 对象。
     */
    fun enqueueMessage(
        channelId: String,
        channelType: ChannelType,
        body: MessageBody,
    ): String {
        val clientMsgNo = nextMsgNo()
        val clientSeq = clientSeqCounter.incrementAndGet()

        val pending = PendingMessage(clientMsgNo, clientSeq, channelId, channelType, body)
        enqueueOrSend(pending)
        return clientMsgNo
    }

    fun subscribe(channelId: String, lastSeq: Long) {
        doOnMainThread {
            val conn = currentConnection ?: return@doOnMainThread
            if (!conn.isActive()) return@doOnMainThread
            conn.sendProto(SubscribePayload(channelId, lastSeq))
        }
    }

    fun unsubscribe(channelId: String) {
        doOnMainThread {
            val conn = currentConnection ?: return@doOnMainThread
            if (!conn.isActive()) return@doOnMainThread
            conn.sendProto(UnsubscribePayload(channelId))
        }
    }

    fun loadHistory(channelId: String, beforeSeq: Long, limit: Int = 50) {
        doOnMainThread {
            val conn = currentConnection ?: return@doOnMainThread
            if (!conn.isActive()) return@doOnMainThread
            conn.sendProto(HistoryLoadPayload(channelId, beforeSeq, limit))
        }
    }

    fun sendTyping(channelId: String, channelType: ChannelType, action: Byte = 0) {
        doOnMainThread {
            val conn = currentConnection ?: return@doOnMainThread
            if (!conn.isActive()) return@doOnMainThread
            val clientMsgNo = nextMsgNo()
            val clientSeq = clientSeqCounter.incrementAndGet()
            val msg = Message(
                MessageHeader(
                    channelId = channelId,
                    clientMsgNo = clientMsgNo,
                    clientSeq = clientSeq,
                    senderUid = uid,
                    channelType = channelType,
                    timestamp = System.currentTimeMillis(),
                ),
                TypingBody(action),
            )
            conn.sendProto(msg)
        }
    }

    // ── ConnectionListener 实现 ──

    override fun onConnected(conn: TcpConnection) {
        connecting = false
        retryCount = 0
        currentConnection = conn
        setState(State.CONNECTED)
        flushPendingQueue()
    }

    override fun onDisconnected(conn: TcpConnection) {
        connecting = false
        if (currentConnection === conn) currentConnection = null
        if (!destroyed) {
            setState(State.CONNECTING)
            scheduleReconnect()
        }
    }

    override fun onAuthFailed(conn: TcpConnection, code: Byte, reason: String) {
        connecting = false
        if (currentConnection === conn) currentConnection = null
        AppLog.e("ImClient", "Auth failed: code=$code reason=$reason")
        if (!destroyed) {
            setState(State.DISCONNECTED)
            stateListener.onAuthFailed(code, reason)
            // 不再 scheduleReconnect() — token 失效重连无意义
        }
    }

    override fun onProtoReceived(conn: TcpConnection, proto: IProto) {
        handleProto(proto)
    }

    // ── IProto 处理 ──

    private fun handleProto(proto: IProto) {
        when (proto) {
            is SendAckPayload -> {
                val callback = pendingAcks.remove(proto.clientMsgNo)
                if (callback != null) {
                    callback.invoke(Result.success(proto))
                    stateListener.onSendAck(proto.clientMsgNo, proto.messageId, proto.serverSeq)
                }
            }

            is Message -> {
                when (proto.body) {
                    is EditBody -> {
                        val body = proto.body as EditBody
                        stateListener.onEditReceived(proto.channelId, body.targetMessageId, body.newContent, body.editedAt)
                    }
                    is TypingBody -> {
                        val body = proto.body as TypingBody
                        stateListener.onTypingReceived(proto.channelId, proto.senderUid ?: "", body.action)
                    }
                    else -> handleMessageProto(proto)
                }
            }

            is PongSignal -> Unit
            is CmdPayload -> {
                stateListener.onCmdReceived(proto.cmdType, proto.payload)
            }
            is AckPayload -> Unit
            is HistoryLoadEndPayload -> {
                stateListener.onHistoryLoadEnd(proto.channelId, proto.beforeSeq, proto.hasMore)
            }
            is PresencePayload -> {
                stateListener.onPresenceReceived(proto.uid, proto.status == 1.toByte(), proto.lastSeenAt)
            }
            is MemberMutedPayload -> {
                stateListener.onMemberMuted(proto.channelId, proto.memberUid, proto.operatorUid, proto.duration)
            }
            is MemberUnmutedPayload -> {
                stateListener.onMemberUnmuted(proto.channelId, proto.memberUid, proto.operatorUid)
            }
            is MemberRoleChangedPayload -> {
                stateListener.onMemberRoleChanged(proto.channelId, proto.memberUid, proto.operatorUid, proto.oldRole, proto.newRole)
            }
            else -> Unit
        }
    }

    private fun handleMessageProto(msg: Message) {
        try {
            stateListener.onMessageReceived(msg)

            // 发送 RECVACK
            val conn = currentConnection ?: return
            conn.sendProto(RecvAckPayload(
                messageId = msg.messageId ?: "",
                channelId = msg.channelId,
                channelType = msg.channelType,
                serverSeq = msg.serverSeq,
            ))
        } catch (e: Exception) {
            AppLog.e("ImClient", "handleMessageProto failed", e)
        }
    }

    // ── 发送队列 ──

    private fun enqueueOrSend(pending: PendingMessage) {
        doOnMainThread {
            val conn = currentConnection
            if (conn != null && conn.isActive()) {
                sendPending(conn, pending)
            } else {
                sendQueue.add(pending)
            }
        }
    }

    private fun sendPending(conn: TcpConnection, pending: PendingMessage) {
        // 构造完整的 Message 对象（header + body）
        val proto = buildMessageFromPending(pending)

        pendingAcks[pending.clientMsgNo] = { result ->
            if (result.isFailure) {
                requeueOrFailed(pending)
            }
        }

        conn.sendProto(proto)

        eventLoop.schedule({
            if (pendingAcks.remove(pending.clientMsgNo) != null) {
                requeueOrFailed(pending)
            }
        }, 5, TimeUnit.SECONDS)
    }

    private fun buildMessageFromPending(pending: PendingMessage): Message {
        val header = MessageHeader(
            channelId = pending.channelId,
            clientMsgNo = pending.clientMsgNo,
            clientSeq = pending.clientSeq,
            senderUid = uid,
            channelType = pending.channelType,
            timestamp = System.currentTimeMillis(),
        )
        return Message(header, pending.body)
    }

    private fun requeueOrFailed(pending: PendingMessage) {
        if (pending.retryCount >= 3) {
            stateListener.onSendFailed(pending.clientMsgNo)
            return
        }
        sendQueue.add(pending.copy(retryCount = pending.retryCount + 1))
    }

    private fun flushPendingQueue() {
        while (true) {
            val conn = currentConnection ?: break
            if (!conn.isActive()) break
            val pending = sendQueue.removeFirstOrNull() ?: break
            sendPending(conn, pending)
        }
    }

    // ── 重连策略 ──

    private fun scheduleReconnect() {
        reconnectSchedule?.cancel(false)
        val delay = nextRetryDelay(retryCount)
        retryCount++
        AppLog.i("ImClient", "Schedule reconnect in ${delay}ms (retry=$retryCount)")
        reconnectSchedule = eventLoop.schedule({ createAndConnect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun nextRetryDelay(count: Int): Long {
        return minOf(30_000L, 1000L * (1L shl minOf(count, 4)))
    }

    private fun createAndConnect() {
        val conn = TcpConnection(host, port, eventLoop, this)
        conn.connect(uid = uid, token = token, deviceId = deviceId)
    }

    // ── 工具方法 ──

    private fun setState(s: State) {
        state = s
        stateListener.onConnectionStateChanged(s)
    }

    private fun doOnMainThread(task: () -> Unit) {
        if (eventLoop.inEventLoop()) {
            task()
        } else {
            eventLoop.execute(task)
        }
    }

    private fun nextMsgNo(): String = "msg-${msgNoCounter.incrementAndGet()}-${System.currentTimeMillis()}"
}
