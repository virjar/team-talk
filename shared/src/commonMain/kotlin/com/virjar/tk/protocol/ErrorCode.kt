package com.virjar.tk.protocol

// ============================================================
// TCP SENDACK 错误码（SendAckPayload.code）
// ============================================================

object SendAckCode {
    const val OK: Byte = 0
    const val RATE_LIMITED: Byte = 1
    const val CHANNEL_NOT_FOUND: Byte = 2
    const val NO_PERMISSION: Byte = 3
    const val PAYLOAD_TOO_LARGE: Byte = 4
    const val DUPLICATE: Byte = 5
    const val CHANNEL_FULL: Byte = 6
    const val CONTENT_REJECTED: Byte = 7
}

// ============================================================
// TCP ACK 通用错误码
// ============================================================

object AckCode {
    const val OK: Byte = 0
    const val INVALID_PACKET: Byte = 1
    const val UNKNOWN_TYPE: Byte = 2
    const val SERVER_ERROR: Byte = 3
}

// ============================================================
// HTTP 模块错误码（Int）
// ============================================================

object AuthErrorCode {
    const val BAD_CREDENTIALS = 10001
    const val TOKEN_EXPIRED = 10002
    const val TOKEN_INVALID = 10003
    const val ACCOUNT_DISABLED = 10004
    const val VALIDATION_ERROR = 10005
    const val USER_ALREADY_EXISTS = 10006
    const val USER_NOT_FOUND = 10007
}

object ContactErrorCode {
    const val APPLY_SELF = 30001
    const val ALREADY_FRIENDS = 30002
    const val APPLY_NOT_FOUND = 30003
}

object ChannelErrorCode {
    const val CHANNEL_NOT_FOUND = 40001
    const val NOT_OWNER = 40002
    const val NOT_MEMBER = 40003
    const val NOT_ADMIN = 40004
    const val INSUFFICIENT_PERMISSION = 40005
    const val MEMBER_MUTED = 40006
    const val CANNOT_MUTE_ADMIN = 40007
    const val CANNOT_KICK_ADMIN = 40008
    const val INVITE_LINK_NOT_FOUND = 40010
    const val INVITE_LINK_EXPIRED = 40011
    const val INVITE_LINK_FULL = 40012
    const val INVITE_LINK_LIMIT = 40013
    const val ALREADY_MEMBER = 40014
}

object MessageErrorCode {
    const val MESSAGE_NOT_FOUND = 50001
    const val REVOKE_NOT_SENDER = 50003
    const val EDIT_NOT_TEXT = 50004
    const val EDIT_NOT_SENDER = 50005
}

object FileErrorCode {
    const val FILE_NOT_FOUND = 60001
    const val UPLOAD_FAILED = 60002
}

object ConversationErrorCode {
    const val NOT_FOUND = 70001
}
