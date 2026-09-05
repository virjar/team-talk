package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 一条稳定文档移动/重命名命令的持久确认。
 *
 * [result] 只在提交变更的那次投递中出现。精确重放通过 [operationId] 证明命令已提交，
 * 但省略可能过期的树投影。
 */
@Serializable
data class DocumentMoveCommandResult(
    val operationId: String,
    val result: DocumentMoveResult?,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(operationId)
        buf.writeBoolean(result != null)
        result?.writeTo(buf)
    }

    companion object : IProtoReader<DocumentMoveCommandResult> {
        override fun readFrom(buf: PacketBuffer): DocumentMoveCommandResult = DocumentMoveCommandResult(
            operationId = buf.readRequiredString(),
            result = if (buf.readBoolean("document move result presence")) {
                DocumentMoveResult.readFrom(buf)
            } else {
                null
            },
        )
    }
}
