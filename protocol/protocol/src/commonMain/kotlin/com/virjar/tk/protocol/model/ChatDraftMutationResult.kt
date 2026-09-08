package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.*
import kotlinx.serialization.Serializable

/** applied/operationRevision are stable receipt facts; current may be newer on an exact retry. */
@SinceProtocol(2)
@Serializable
data class ChatDraftMutationResult(val applied: Boolean, val operationRevision: Long, val current: ChatDraftSnapshot) : IProto {
    init { require(if (applied) operationRevision in 1..current.revision else operationRevision == 0L) }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeBoolean(applied); buf.writeVarLong(operationRevision); current.writeTo(buf)
    }
    companion object : IProtoReader<ChatDraftMutationResult> {
        override fun readFrom(buf: PacketBuffer) = ChatDraftMutationResult(buf.readBoolean(), buf.readVarLong(), ChatDraftSnapshot.readFrom(buf))
    }
}
