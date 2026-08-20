package com.virjar.tk.ui.bridge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.virjar.tk.model.Message
import com.virjar.tk.ui.component.FileDownloadController

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
 *         onPickImage = { imagePicker.launch(...) },
 *         imageContent = { url, mod -> AsyncImage(url, mod) },
 *         onMediaClick = rememberMediaClickHandler(...),
 *     )
 * }
 * ChatPanel(..., media = media)
 * ```
 */
data class ChatMediaConfig(
    /** 选择图片发送。 */
    val onPickImage: (() -> Unit)? = null,
    /** 选择文件发送。 */
    val onPickFile: (() -> Unit)? = null,
    /** 选择视频发送。null=附件面板不显示视频项。 */
    val onPickVideo: (() -> Unit)? = null,
    /** 语音录制：true=开始，false=停止发送。 */
    val onVoiceRecord: ((Boolean) -> Unit)? = null,
    /** 进入语音模式；Android 用于在用户真正长按前申请麦克风权限。 */
    val onVoiceModeEntered: (() -> Unit)? = null,
    /** 指针手势取消或录音控件离开组合：停止并丢弃，不得发送残片。 */
    val onVoiceRecordCancel: (() -> Unit)? = null,
    /** 图片消息内容渲染器。null=回退到 MediaCard。 */
    val imageContent: @Composable ((url: String, modifier: Modifier) -> Unit)? = null,
    /** 视频消息内容渲染器。null=回退到 MediaCard。 */
    val videoContent: @Composable ((url: String, modifier: Modifier) -> Unit)? = null,
    /** 媒体点击处理。null=不可点击。 */
    val onMediaClick: ((message: Message) -> Unit)? = null,
    /** 富文本 @提及 点击（打开用户资料）。 */
    val onMentionClick: ((uid: String) -> Unit)? = null,
    /** 富文本超链接点击（桌面开浏览器/Android ACTION_VIEW）。 */
    val onUrlClick: ((String) -> Unit)? = null,
    /** 文件附件下载控制器（小文件静默/大文件点击/进度动画）。null=回退 onMediaClick。 */
    val fileDownloads: FileDownloadController? = null,
)
