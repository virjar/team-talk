package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.protocol.model.Chat
import java.util.UUID

/** 测试夹具便捷方法；生产 RPC 调用方必须提供自己的稳定 operation id。 */
suspend fun ChatService.createGroup(
    name: String,
    avatar: String?,
    creatorUid: String,
    memberUids: List<String>,
): Chat = createGroup(
    operationId = UUID.randomUUID().toString(),
    name = name,
    avatar = avatar,
    creatorUid = creatorUid,
    memberUids = memberUids,
)
