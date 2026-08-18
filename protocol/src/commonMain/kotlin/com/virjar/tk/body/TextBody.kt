@file:Suppress("DEPRECATION")

package com.virjar.tk.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * 旧版纯文本消息体，仅用于读取历史 `MessageType.TEXT(code=1)` 消息。
 *
 * 新消息统一使用 [RichTextBody]：即使内容没有格式标记，也以 Markdown 作为唯一文本源，
 * 避免发送、编辑、回复和 Bot 在 TEXT/RICH_TEXT 两套契约间分叉。
 */
@Deprecated(
    message = "TextBody 仅用于旧消息兼容；新文本消息请使用 buildRichTextBody(markdown)",
    replaceWith = ReplaceWith("buildRichTextBody(text)"),
)
data class TextBody(val text: String) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(text)
    }

    companion object : IProtoReader<TextBody> {
        override fun readFrom(buf: PacketBuffer): TextBody = TextBody(
            text = buf.readString()!!
        )
    }
}
