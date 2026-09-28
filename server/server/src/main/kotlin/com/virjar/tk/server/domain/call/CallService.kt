package com.virjar.tk.server.domain.call

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.CallEventPayload
import com.virjar.tk.protocol.CallSignalPayload
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallEventKind
import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.model.IceServer
import com.virjar.tk.server.domain.event.TransientEventPublisher
import com.virjar.tk.server.domain.presence.PresenceTransition
import com.virjar.tk.server.domain.presence.PresenceTransitionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** 用户在线判定端口；实现由连接注册表提供，domain 不感知连接。 */
fun interface CallOnlineChecker {
    suspend fun isOnline(uid: String): Boolean
}

/** 按呼叫签发 ICE 服务器（含短期 TURN 凭据）；未部署 TURN 时返回空列表（仅直连）。 */
fun interface CallIceServers {
    suspend fun issue(validForSec: Long): List<IceServer>
}

/** 通话准入：好友关系与双向拉黑由联系人域裁决（服务端权威事实）。 */
fun interface CallAdmission {
    fun canCall(callerUid: String, calleeUid: String): Boolean
}

/** 双方私聊会话定位（幂等，必要时创建）；返回 chatId。 */
fun interface CallChatSessions {
    suspend fun ensurePersonalChat(callerUid: String, calleeUid: String): String
}

/** CALL_LOG 记录落库（走普通消息链，离线推送自然生效）。 */
fun interface CallLogSink {
    suspend fun append(
        chatId: String,
        callId: String,
        callerUid: String,
        calleeUid: String,
        video: Boolean,
        durationSec: Int,
        reasonCode: Int,
    )
}

/**
 * 1:1 通话的呼叫状态机（协议 minor 0.5）。
 *
 * 呼叫是进程内短期事实：振铃→接通→终结，信令经 [TransientEventPublisher] 瞬时直达，
 * 不进入持久事件流。唯一落库的是终结时的 CALL_LOG 记录（走普通消息链，离线推送自然生效）。
 * 崩溃或重启即全部呼叫消失，两端以本地超时收敛——这是有意的最小语义，不做呼叫恢复。
 *
 * 超时回收：振铃超时由 [sweepTimeouts]（MaintenanceWorker 驱动）终结；
 * 参与方全部设备离线由 [onTransition] 观察并按连接中断终结。
 */
class CallService(
    private val admission: CallAdmission,
    private val sessions: CallChatSessions,
    private val callLog: CallLogSink,
    private val publisher: TransientEventPublisher,
    private val onlineChecker: CallOnlineChecker,
    private val iceServers: CallIceServers,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PresenceTransitionObserver, AutoCloseable {

    private val logger = LoggerFactory.getLogger(CallService::class.java)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callsByCallId = ConcurrentHashMap<String, CallSession>()
    private val activeCallByUid = ConcurrentHashMap<String, String>()

    private class CallSession(
        val callId: String,
        val callerUid: String,
        val calleeUid: String,
        val video: Boolean,
        var state: State,
        var acceptedAtMillis: Long,
        var ringDeadlineMillis: Long,
    )

    private enum class State { RINGING, ACTIVE }

    /**
     * 主叫发起。仅好友可通话（双向拉黑拒绝）；自身忙线与对端忙线都收敛为 busy outcome，
     * 对端离线返回不可达——这些都不打断被叫。可达时向被叫瞬时投递 RING（携带 ICE 凭据）。
     */
    suspend fun invite(callerUid: String, callId: String, calleeUid: String, video: Boolean): CallInviteOutcome {
        if (!admission.canCall(callerUid, calleeUid)) {
            throw IllegalArgumentException("仅好友之间可以发起通话")
        }
        mutex.withLock {
            if (activeCallByUid.containsKey(callerUid) || activeCallByUid.containsKey(calleeUid)) {
                return CallInviteOutcome(deliverable = false, busy = true, iceServers = emptyList())
            }
            val online = onlineChecker.isOnline(calleeUid)
            if (!online) {
                // 从未进入振铃表：直接按无应答终结（落未接记录并通知双方）
                finish(newRingingSession(callId, callerUid, calleeUid, video), CallEndReason.TIMEOUT)
                return CallInviteOutcome(deliverable = false, busy = false, iceServers = emptyList())
            }
            val session = newRingingSession(callId, callerUid, calleeUid, video)
            callsByCallId[callId] = session
            activeCallByUid[callerUid] = callId
            activeCallByUid[calleeUid] = callId
            publisher.emitTransient(
                calleeUid,
                NotifyType.CALL_EVENT,
                CallEventPayload(
                    callId = callId,
                    fromUid = callerUid,
                    kind = CallEventKind.RING,
                    video = video,
                    endReasonCode = 0,
                    iceServers = iceServers.issue(ICE_CREDENTIAL_VALID_SEC),
                ),
            )
            return CallInviteOutcome(deliverable = true, busy = false, iceServers = iceServers.issue(ICE_CREDENTIAL_VALID_SEC))
        }
    }

    /** 被叫应答；接受后向主叫投递 ACCEPTED。呼叫不存在或已终结返回 false。 */
    suspend fun answer(calleeUid: String, callId: String, accept: Boolean): Boolean {
        mutex.withLock {
            val session = callsByCallId[callId] ?: return false
            if (session.calleeUid != calleeUid || session.state != State.RINGING) return false
            if (!accept) {
                finishLocked(session, CallEndReason.DECLINED)
                return true
            }
            session.state = State.ACTIVE
            session.acceptedAtMillis = nowMillis()
            publisher.emitTransient(
                session.callerUid,
                NotifyType.CALL_EVENT,
                CallEventPayload(
                    callId = callId, fromUid = calleeUid, kind = CallEventKind.ACCEPTED,
                    video = session.video, endReasonCode = 0, iceServers = emptyList(),
                ),
            )
            return true
        }
    }

    /** 媒体协商中继：原样转发给参与对端，不理解内容。 */
    suspend fun signal(senderUid: String, callId: String, body: CallSignalBody): Boolean {
        mutex.withLock {
            val session = callsByCallId[callId] ?: return false
            val peer = session.peerOf(senderUid) ?: return false
            publisher.emitTransient(
                peer,
                NotifyType.CALL_SIGNAL,
                CallSignalPayload(callId = callId, body = body),
            )
            return true
        }
    }

    /** 参与方终结呼叫；只接受已定义的原因，未知值按挂断处理。 */
    suspend fun hangup(uid: String, callId: String, reasonCode: Int): Boolean {
        mutex.withLock {
            val session = callsByCallId[callId] ?: return false
            if (session.peerOf(uid) == null) return false
            val reason = CallEndReason.entries.firstOrNull { it.code == reasonCode } ?: CallEndReason.HANGUP
            finishLocked(session, reason)
            return true
        }
    }

    /** MaintenanceWorker 驱动：终结超时仍未接听的振铃。 */
    suspend fun sweepTimeouts() {
        val now = nowMillis()
        val expired = mutex.withLock {
            callsByCallId.values.filter { it.state == State.RINGING && it.ringDeadlineMillis <= now }
                .onEach { finishLocked(it, CallEndReason.TIMEOUT) }
        }
        if (expired.isNotEmpty()) {
            logger.info("通话振铃超时终结: {} 个", expired.size)
        }
    }

    /** 参与方全部设备离线时按连接中断终结（非阻塞观察者，副作用异步执行）。 */
    override fun onTransition(transition: PresenceTransition) {
        if (transition.online) return
        val affected = callsByCallId.values.filter {
            it.callerUid == transition.uid || it.calleeUid == transition.uid
        }
        affected.forEach { session ->
            scope.launch {
                try {
                    finish(session, CallEndReason.CONNECTION_LOST)
                } catch (e: Exception) {
                    logger.warn("断连终结呼叫失败: callId={}", session.callId)
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun newRingingSession(callId: String, callerUid: String, calleeUid: String, video: Boolean) =
        CallSession(
            callId = callId,
            callerUid = callerUid,
            calleeUid = calleeUid,
            video = video,
            state = State.RINGING,
            acceptedAtMillis = 0,
            ringDeadlineMillis = nowMillis() + RINGING_TIMEOUT_MILLIS,
        )

    /** 锁内终结：先移除表项保证幂等（重入直接返回），再串行投递信令与落库。 */
    private suspend fun finishLocked(session: CallSession, reason: CallEndReason) {
        if (callsByCallId.remove(session.callId) == null) return
        activeCallByUid.remove(session.callerUid, session.callId)
        activeCallByUid.remove(session.calleeUid, session.callId)
        finish(session, reason)
    }

    private suspend fun finish(session: CallSession, reason: CallEndReason) {
        val durationSec = if (session.state == State.ACTIVE && session.acceptedAtMillis > 0) {
            ((nowMillis() - session.acceptedAtMillis) / 1000).toInt().coerceAtLeast(0)
        } else {
            0
        }
        // 对端与本端都以 ENDED 收敛（超时/断连时两端都需要被告知）
        listOf(session.callerUid, session.calleeUid).forEach { uid ->
            runCatching {
                publisher.emitTransient(
                    uid,
                    NotifyType.CALL_EVENT,
                    CallEventPayload(
                        callId = session.callId, fromUid = session.callerUid, kind = CallEventKind.ENDED,
                        video = session.video, endReasonCode = reason.code, iceServers = emptyList(),
                    ),
                )
            }.onFailure { logger.warn("ENDED 信令投递失败: callId={}", session.callId) }
        }
        runCatching {
            val chatId = sessions.ensurePersonalChat(session.callerUid, session.calleeUid)
            callLog.append(
                chatId = chatId,
                callId = session.callId,
                callerUid = session.callerUid,
                calleeUid = session.calleeUid,
                video = session.video,
                durationSec = durationSec,
                reasonCode = reason.code,
            )
        }.onFailure { failure ->
            logger.warn("CALL_LOG 落库失败: callId={}", session.callId, failure)
        }
    }

    private fun CallSession.peerOf(uid: String): String? = when (uid) {
        callerUid -> calleeUid
        calleeUid -> callerUid
        else -> null
    }

    companion object {
        const val RINGING_TIMEOUT_MILLIS = 45_000L
        const val ICE_CREDENTIAL_VALID_SEC = 3_600L
    }
}
