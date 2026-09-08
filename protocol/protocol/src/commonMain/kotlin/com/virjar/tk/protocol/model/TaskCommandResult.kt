package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 精确命令已成功；当前不再有读取权限时只确认成功，不泄露旧任务快照。 */
@SinceProtocol(2)
@Serializable
data class TaskCommandResult(val task: WorkTask?) : IProto {
    override fun writeTo(buf: PacketBuffer) { buf.writeBoolean(task != null); task?.writeTo(buf) }
    companion object : IProtoReader<TaskCommandResult> {
        override fun readFrom(buf: PacketBuffer) = TaskCommandResult(if (buf.readBoolean()) WorkTask.readFrom(buf) else null)
    }
}
