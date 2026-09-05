package com.virjar.tk.server.infra.sync

import com.virjar.tk.protocol.payload.NotifyPayload

/** 进程本地实时投递适配器。持久重放不依赖此调用成功。 */
fun interface LiveEventSink {
    suspend fun push(uid: String, notify: NotifyPayload)
}
