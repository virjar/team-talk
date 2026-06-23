package com.virjar.tk.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.model.Message

/**
 * 平台媒体操作接口。两端各提供实现，注入给 [rememberMediaClickHandler]。
 *
 * 抽象目的：聊天页点击媒体消息的**分发逻辑**（哪种 body 走哪个动作）两端完全一致，
 * 只有底层 helper 不同（Android: VoicePlayer/MediaHelper；Desktop: DesktopMediaHelper）。
 * 用此接口把分发逻辑收敛到 commonMain，平台只提供三个回调。
 */
interface PlatformMediaActions {
    /** 播放语音（Android: VoicePlayer.play；Desktop: DesktopMediaHelper.playAudio） */
    fun playVoice(url: String)

    /** 打开/下载文件（Android: MediaHelper.openFile；Desktop: DesktopMediaHelper.openFile） */
    fun openFile(url: String)

    /** 打开全屏媒体画廊（Android: 设 state 触发 MediaGallery；Desktop: 设 state 触发 MediaGalleryWindow） */
    fun showGallery(items: List<GalleryItem>, index: Int)
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
    return remember(actions) {
        { msg: Message ->
            when (msg.body) {
                is ImageBody, is VideoBody -> {
                    val mediaList = buildMediaList(messages.value)
                    val index = when (msg.body) {
                        is ImageBody -> mediaList.indexOfFirst { it.url == (msg.body as ImageBody).url }
                        is VideoBody -> {
                            val body = msg.body as VideoBody
                            mediaList.indexOfFirst { it.url == body.url }
                        }
                        else -> 0
                    }.coerceAtLeast(0)
                    actions.showGallery(mediaList, index)
                }
                is VoiceBody -> actions.playVoice((msg.body as VoiceBody).url)
                is FileBody -> actions.openFile((msg.body as FileBody).url)
                else -> {}
            }
        }
    }
}
