package com.virjar.tk.app.ui.component

internal const val CHAT_ATTACHMENT_PANEL_TEST_TAG = "chat.attach.panel"
internal const val CHAT_ATTACHMENT_IMAGE_TEST_TAG = "chat.attach.image"
internal const val CHAT_ATTACHMENT_VIDEO_TEST_TAG = "chat.attach.video"
internal const val CHAT_ATTACHMENT_FILE_TEST_TAG = "chat.attach.file"
internal const val CHAT_ATTACHMENT_PASTE_TEST_TAG = "chat.attach.paste"
internal const val CHAT_VOICE_MODE_TEST_TAG = "chat.voiceMode"
internal const val CHAT_VOICE_RECORD_TEST_TAG = "chat.voice.record"
internal const val CHAT_PREVIEW_TEST_TAG = "chat.preview"

internal enum class ChatMessageMediaKind(val tagSegment: String) {
    FILE("file"),
    IMAGE("image"),
    VOICE("voice"),
    VIDEO("video"),
}

/** 乐观媒体与其服务端确认替换项共用的稳定 UI 身份。 */
internal fun chatMessageMediaTestTag(
    serverSeq: Long,
    clientMsgId: String,
    kind: ChatMessageMediaKind,
): String {
    val messageIdentity = if (serverSeq > 0L) {
        "seq.$serverSeq"
    } else {
        "client.${clientMsgId.take(12).ifEmpty { "unknown" }}"
    }
    return "chat.message.$messageIdentity.media.${kind.tagSegment}"
}
