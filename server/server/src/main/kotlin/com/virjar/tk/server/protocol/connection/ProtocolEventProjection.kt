package com.virjar.tk.server.protocol.connection

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtocolWireRegistry
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload

/**
 * Per-connection wire projection; the original durable event and its stored bytes never change.
 * An unsupported durable event still advances the client's cursor, including an entire skipped page.
 * New business projections must provide a snapshot/bootstrap path when a client later upgrades.
 */
internal fun eventFrameForProtocol(frame: IProto, version: ProtocolVersion): IProto? = when (frame) {
    is NotifyPayload -> notificationForProtocol(frame, version)
    is SyncBatchPayload -> SyncBatchPayload(
        frame.events.map { checkNotNull(notificationForProtocol(it, version)) },
    )
    else -> frame
}

private fun notificationForProtocol(event: NotifyPayload, version: ProtocolVersion): NotifyPayload? {
    val knownNotification = ProtocolWireRegistry.supportsNotifyType(event.notifyType, version)
    val supported = knownNotification && when (event.notifyType) {
        NotifyType.MESSAGE_RECV.code, NotifyType.TYPING.code -> {
            // Only read the bounded message header; event delivery never decodes a large body twice.
            val messageType = Message.readMessageType(checkNotNull(event.payload))
            ProtocolWireRegistry.supportsMessageType(messageType, version)
        }
        else -> true
    }
    return when {
        supported -> event
        event.eventId > 0L -> NotifyPayload(event.eventId, NotifyType.EVENT_CURSOR_ADVANCED.code, null)
        else -> null
    }
}
