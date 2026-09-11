package com.virjar.tk.app.navigation.feature.chat

import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.app.ui.component.rich.ChatComposerMode

/**
 * 普通跨设备镜像只接受独立 Markdown 字符串，不发布缺少 sidecar 的内部 URI。
 * 完整本机正文与资产由 ChatDraftSnapshot 持久化，此函数同时服务尚无持久适配器的展示夹具。
 */
internal fun durableChatDraftMirrorPayload(markdown: String): String =
    runCatching {
        markdown.takeIf { MarkdownAssetPolicy.references(it).isEmpty() }.orEmpty()
    }.getOrDefault("")
/** 只保存稳定引用；Message 由当前窗口或独立的权威单条读取恢复。 */
internal data class SavedChatReplyTarget(val clientMsgId: String = "", val serverSeq: Long = 0L) {
    internal fun bind(messages: List<Message>): Message? =
        clientMsgId.takeIf(String::isNotEmpty)?.let { targetId ->
            messages.firstOrNull {
                it.clientMsgId == targetId && (serverSeq == 0L || it.serverSeq == serverSeq) &&
                    it.flags and Message.FLAG_REVOKED == 0
            }
        }
}
/**
 * 编辑已发消息时的可恢复会话。这里只保存平台 Saver 支持的稳定值；目标消息和回复消息
 * 均用 clientMsgId 在当前消息流中重新绑定，Activity 重建不会把被编辑正文误当普通草稿。
 */
internal data class SavedChatEditingSession(
    val editingClientMsgId: String = "",
    val targetLoaded: Boolean = false,
    /**
     * 仅对本次编辑尝试稳定。普通草稿与编辑尝试启动的内嵌资源上传必须使用不同的 owner，
     * 否则迟到的 READY 帧可能被错误的编辑器上下文消费，使挂起的草稿缺少其描述符。
     */
    val assetImportOwnerId: String = "",
    val suspendedMarkdown: String = "",
    val suspendedMode: ChatComposerMode = ChatComposerMode.VISUAL,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val replyingClientMsgId: String = "",
    val replyingServerSeq: Long = 0L,
    val suspendedAssets: List<EmbeddedAsset> = emptyList(),
)
internal fun chatEmbeddedAssetImportOwnerKey(
    chatId: String,
    editingSession: SavedChatEditingSession,
): String = if (editingSession.editingClientMsgId.isEmpty()) {
    "chat:$chatId:draft"
} else {
    val editAttemptId = editingSession.assetImportOwnerId.ifEmpty {
        // 对内部畸形的恢复状态失败关闭（fail closed），且不会回退到普通草稿 owner。
        // 新的编辑尝试总是携带随机 owner id。
        "invalid-${editingSession.editingClientMsgId}"
    }
    "chat:$chatId:edit:$editAttemptId"
}
