package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/**
 * 1:1 通话信令 IDL。
 *
 * - invite：主叫发起，服务端裁决可达性与忙线并签发 ICE 凭据；被叫在线则瞬时投递 CALL_EVENT(RING)。
 * - answer：被叫接听/拒接；接受时服务端向主叫投递 CALL_EVENT(ACCEPTED)。
 * - signal：媒体协商中继（SDP/ICE），服务端按呼叫表原样转发为 CALL_SIGNAL。
 * - hangup：任意一方终结呼叫，reason 是 [CallEndReason.code]；服务端向对端投递 CALL_EVENT(ENDED)。
 *
 * 信令为尽力而为：呼叫不在服务端活跃表中时以上命令按业务失败拒绝，双方以本地超时收敛。
 * 媒体流不经过服务端（P2P 直连，内嵌 TURN 仅在直连失败时中继 UDP）。
 */
@SinceProtocol(5)
@RpcService("call")
interface CallRpc {
    @RpcMethod(1)
    suspend fun invite(callId: String, calleeUid: String, video: Boolean): CallInviteOutcome

    @RpcMethod(2)
    suspend fun answer(callId: String, accept: Boolean): Boolean

    @RpcMethod(3)
    suspend fun signal(callId: String, body: CallSignalBody): Boolean

    @RpcMethod(4)
    suspend fun hangup(callId: String, reason: Int): Boolean
}
