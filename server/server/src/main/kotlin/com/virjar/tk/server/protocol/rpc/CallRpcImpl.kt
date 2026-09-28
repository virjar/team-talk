package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.model.CallInviteOutcome
import com.virjar.tk.protocol.model.CallSignalBody
import com.virjar.tk.protocol.rpc.gen.CallRpcStub
import com.virjar.tk.server.domain.call.CallService

class CallRpcImpl(uid: String, private val calls: CallService) : CallRpcStub(uid) {
    override suspend fun invite(callId: String, calleeUid: String, video: Boolean): CallInviteOutcome =
        calls.invite(uid, callId, calleeUid, video)

    override suspend fun answer(callId: String, accept: Boolean): Boolean =
        calls.answer(uid, callId, accept)

    override suspend fun signal(callId: String, body: CallSignalBody): Boolean =
        calls.signal(uid, callId, body)

    override suspend fun hangup(callId: String, reason: Int): Boolean =
        calls.hangup(uid, callId, reason)
}
