package com.virjar.tk.infra.sync

import com.virjar.tk.protocol.payload.NotifyPayload

/** Process-local live delivery adapter. Durable replay does not depend on this call succeeding. */
fun interface LiveEventSink {
    suspend fun push(uid: String, notify: NotifyPayload)
}
