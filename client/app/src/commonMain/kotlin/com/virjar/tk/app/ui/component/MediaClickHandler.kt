package com.virjar.tk.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.VideoBody
import com.virjar.tk.protocol.body.VoiceBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset

/**
 * 平台媒体操作接口。两端各提供实现，注入给 [rememberMediaClickHandler]。
 *
 * 抽象目的：聊天页点击媒体消息的**分发逻辑**（哪种 body 走哪个动作）两端完全一致，
 * 只有底层会话媒体实现不同（Android 平台播放器；Desktop 会话资源）。
 * 用此接口把分发逻辑收敛到 commonMain，平台只提供三个回调。
 */
interface PlatformMediaActions {
    /** 播放语音（由平台会话播放器实现）。 */
    fun playVoice(attachment: Attachment)

    /** 打开或下载文件（由平台认证会话资源实现）。 */
    fun openFile(attachment: Attachment)

    /** 打开全屏媒体画廊（Android: 设 state 触发 MediaGallery；Desktop: 设 state 触发 MediaGalleryWindow） */
    fun showGallery(items: List<GalleryItem>, index: Int)

    /**
     * 打开类型化办公对象引用：平台必须先经域读入口（DocumentRpc.getDocument /
     * GroupFileRpc.getEntry）重校验当前权限，成功再导航，失败给出安全降级提示。
     * 默认实现表示平台尚未接线（卡片不可点击之外的最后防线）。
     */
    fun openOfficeRef(message: Message, body: com.virjar.tk.protocol.body.OfficeRefBody) {}
}

/**
 * 共享的媒体点击处理器。
 *
 * 封装 [ChatPanel.onMediaClick] 的分发逻辑：
 * - [ImageBody] / [VideoBody] → [buildMediaList] + 计算 index → [PlatformMediaActions.showGallery]
 * - [VoiceBody] → [PlatformMediaActions.playVoice]
 * - [FileBody] → [PlatformMediaActions.openFile]
 *
 * 平台只需提供 [PlatformMediaActions] 实现，无需各自重写 when 分发。
 *
 * @param messages 当前聊天消息列表的 State（用于构建画廊媒体列表）
 * @param actions 平台媒体操作回调
 * @return 供 ChatPanel.onMediaClick 使用的 (Message) -> Unit
 */
@Composable
fun rememberMediaClickHandler(
    messages: State<List<Message>>,
    actions: PlatformMediaActions,
): (Message) -> Unit {
    return remember(messages, actions) {
        { msg: Message ->
            when (msg.body) {
                is ImageBody, is VideoBody -> {
                    // clientMsgId 只在单个聊天内唯一。平台宿主从 A 切换到 B 时，collectAsState
                    // 可能把其持有者再保留一帧，因此画廊范围必须由被点击的消息定义，
                    // 而不能信任当前列表。
                    val mediaList = buildMediaList(messages.value.filter { it.chatId == msg.chatId })
                    // 转发/重发后附件 path 完全可能重复。初始页由被点击消息的身份选取，
                    // 而不是其 blob path。
                    val index = mediaList.indexOfFirst {
                        it.sourceMessageId == msg.clientMsgId && it.sourceAssetId == null
                    }
                    if (index >= 0) actions.showGallery(mediaList, index)
                }
                is VoiceBody -> actions.playVoice((msg.body as VoiceBody).attachment)
                is FileBody -> actions.openFile((msg.body as FileBody).attachment)
                is com.virjar.tk.protocol.body.OfficeRefBody ->
                    actions.openOfficeRef(msg, msg.body as com.virjar.tk.protocol.body.OfficeRefBody)
                else -> {}
            }
        }
    }
}

/** 在同一会话画廊中打开 Markdown 消息内嵌的单张图片。 */
@Composable
fun rememberEmbeddedMediaClickHandler(
    messages: State<List<Message>>,
    actions: PlatformMediaActions,
): (Message, EmbeddedAsset) -> Unit = remember(messages, actions) {
    { message, asset ->
        val mediaList = buildMediaList(messages.value.filter { it.chatId == message.chatId })
        val index = mediaList.indexOfFirst {
            it.sourceMessageId == message.clientMsgId && it.sourceAssetId == asset.assetId
        }
        if (index >= 0) actions.showGallery(mediaList, index)
    }
}
