package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtocolWireRegistry
import com.virjar.tk.protocol.model.Message

/**
 * Preserve identity and history coordinates without exposing an undecodable body to a negotiated
 * older peer: the placeholder keeps the original type code, drops the body and marks
 * FLAG_PROJECTION_PLACEHOLDER（与同步事件投影同形态）——旧客户端 null-body 兜底渲染
 * "当前版本不支持"，升级后的客户端按标记重拉历史自愈。
 */
internal fun Message.forProtocol(version: ProtocolVersion): Message =
    if (ProtocolWireRegistry.supportsMessageType(messageType, version)) this
    else copy(flags = flags or Message.FLAG_PROJECTION_PLACEHOLDER, body = null)
