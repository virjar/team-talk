package com.virjar.tk.ui.bridge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.virjar.tk.model.Message

/**
 * ChatPanel 媒体能力配置：收敛 7 个平台相关 lambda/Composable 为一个参数。
 *
 * 各平台在 Composable 作用域内构造此对象（picker launcher 需要 Composable 上下文），
 * 传给 ChatPanel 的 `media` 参数。
 *
 * 示例：
 * ```
 * val media = remember(chatId) {
 *     ChatMediaConfig(
 *         onAttachClick = { showAttachSheet = true },
 *         onPickImage = { imagePicker.launch(...) },
 *         imageContent = { url, mod -> AsyncImage(url, mod) },
 *         onMediaClick = rememberMediaClickHandler(...),
 *     )
 * }
 * ChatPanel(..., media = media)
 * ```
 */
data class ChatMediaConfig(
    /** 附件(+)按钮点击。null=不显示附件工具栏。 */
    val onAttachClick: (() -> Unit)? = null,
    /** 选择图片发送。 */
    val onPickImage: (() -> Unit)? = null,
    /** 选择文件发送。 */
    val onPickFile: (() -> Unit)? = null,
    /** 语音录制：true=开始，false=停止发送。 */
    val onVoiceRecord: ((Boolean) -> Unit)? = null,
    /** 图片消息内容渲染器。null=回退到 MediaCard。 */
    val imageContent: @Composable ((url: String, modifier: Modifier) -> Unit)? = null,
    /** 视频消息内容渲染器。null=回退到 MediaCard。 */
    val videoContent: @Composable ((url: String, modifier: Modifier) -> Unit)? = null,
    /** 媒体点击处理。null=不可点击。 */
    val onMediaClick: ((message: Message) -> Unit)? = null,
) {
    /** 是否有任何媒体发送能力（决定附件工具栏是否显示）。 */
    val hasSendCapability: Boolean
        get() = onAttachClick != null || onPickImage != null || onPickFile != null || onVoiceRecord != null
}
