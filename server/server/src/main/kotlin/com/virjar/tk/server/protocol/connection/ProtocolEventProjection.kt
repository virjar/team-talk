package com.virjar.tk.server.protocol.connection

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtocolWireRegistry
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload

/**
 * Per-connection wire projection; the original durable event and its stored bytes never change.
 * An unsupported durable event still advances the client's cursor, including an entire skipped page.
 * New business projections must provide a snapshot/bootstrap path when a client later upgrades.
 *
 * 消息事件的对端降级不是"跳过"而是"占位"（[Message.readProjectionPlaceholder]）：保留身份
 * 与原始类型码、剥正文、打 FLAG_PROJECTION_PLACEHOLDER——旧客户端红点背后有可见的
 * "当前版本不支持"消息，升级后按标记重拉历史自愈。
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
        NotifyType.MESSAGE_RECV.code -> {
            // Only read the bounded message header; event delivery never decodes a large body twice.
            val payload = checkNotNull(event.payload)
            if (ProtocolWireRegistry.supportsMessageType(Message.readMessageType(payload), version)) {
                true
            } else {
                return NotifyPayload(
                    event.eventId,
                    event.notifyType,
                    ProtoCodec.encode(Message.readProjectionPlaceholder(payload)),
                )
            }
        }
        NotifyType.TYPING.code -> {
            ProtocolWireRegistry.supportsMessageType(
                Message.readMessageType(checkNotNull(event.payload)),
                version,
            )
        }
        else -> true
    }
    return when {
        supported -> event
        event.eventId > 0L -> NotifyPayload(event.eventId, NotifyType.EVENT_CURSOR_ADVANCED.code, null)
        else -> null
    }
}
