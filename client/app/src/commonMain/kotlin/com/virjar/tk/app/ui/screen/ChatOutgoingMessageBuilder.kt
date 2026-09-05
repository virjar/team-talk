package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.app.ui.component.MessagePreview
import java.util.UUID

/** 构建普通发送与回复发送共享的那条未校验的本地发出消息行。 */
internal fun buildOutgoingChatMessageOrReport(
    chatId: String,
    myUid: String,
    inputText: String,
    embeddedAssets: List<EmbeddedAsset>,
    replyingTo: Message?,
    resolveSender: ((uid: String) -> User?)?,
    reportError: (String) -> Unit,
    buildRichTextBody: (String, List<EmbeddedAsset>) -> RichTextBody?,
): Message? {
    // 回复与普通消息共享同一 Markdown/sidecar 准入。在这里构建一次可保持 manifest 规范，
    // 并防止回复发送随着内嵌内容演进漂移进第二条富文本路径。
    val richBody = buildRichTextBody(inputText, embeddedAssets) ?: return null
    if (replyingTo == null) {
        return Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = myUid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = richBody,
        )
    }
    val replyToMsgId = replyingTo.confirmedReplyToMsgIdOrNull() ?: run {
        reportError("发送失败: 回复目标尚未被服务器确认")
        return null
    }
    return Message(
        chatId = chatId,
        clientMsgId = UUID.randomUUID().toString(),
        senderUid = myUid,
        messageType = MessageType.REPLY.code,
        timestamp = System.currentTimeMillis(),
        body = ReplyBody(
            replyToMsgId = replyToMsgId,
            replyToSenderUid = replyingTo.senderUid,
            replyToSenderName = resolveDisplayNameOrNull(replyingTo.senderUid, resolveSender),
            replySnippet = MessagePreview.preview(replyingTo).take(50),
            content = richBody.markdown,
            assets = richBody.assets,
        ),
    )
}
