package com.virjar.tk.protocol

import io.netty.handler.codec.CorruptedFrameException

/**
 * The peer sent a structurally valid TeamTalk authentication preamble for a protocol version
 * that this endpoint cannot decode.
 *
 * This is deliberately narrower than [CorruptedFrameException]: invalid magic, a bad tail byte,
 * truncated frames and arbitrary codec failures must never be promoted to an upgrade signal.
 */
class ProtocolVersionMismatchException(
    val receivedVersion: Int,
    val supportedVersion: Int,
) : CorruptedFrameException(
    "Unsupported TeamTalk protocol version $receivedVersion (supported=$supportedVersion)",
)
