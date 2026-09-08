package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.model.TaskPolicy

/** 发送时由服务端重建冻结预览；打开仍需读取当前任务，不授予接收者访问权。 */
@SinceProtocol(2)
data class TaskRefBody(val taskId: String, val title: String, val subtitle: String = "") : MessageBody {
    init {
        TaskPolicy.requireId(taskId)
        require(title.isNotBlank() && title.length <= TaskPolicy.MAX_TITLE_LENGTH && title.none(Char::isISOControl))
        require(subtitle.length <= MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH && '\u0000' !in subtitle)
    }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(taskId); buf.writeString(title); buf.writeString(subtitle) }
    companion object : IProtoReader<TaskRefBody> {
        override fun readFrom(buf: PacketBuffer) = TaskRefBody(buf.readRequiredString(36),
            buf.readRequiredString(TaskPolicy.MAX_TITLE_LENGTH * 4), buf.readRequiredString(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH * 4))
    }
}
