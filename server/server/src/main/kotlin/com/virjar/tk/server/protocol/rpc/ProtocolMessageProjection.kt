package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtocolWireRegistry
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message

/** Preserve history coordinates without exposing an undecodable body to a negotiated older peer. */
internal fun Message.forProtocol(version: ProtocolVersion): Message =
    if (ProtocolWireRegistry.supportsMessageType(messageType, version)) this
    else copy(messageType = MessageType.RICH_TEXT.code, body = buildRichTextBody("此消息需要升级客户端查看"))
