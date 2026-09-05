package com.virjar.tk.app.ui.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.component.FileDownloadController

/**
 * ChatPanel 媒体能力配置：集中声明平台渲染、资源导入和录制能力。
 *
 * 平台 Host 在 Composable 作用域内构造后传给 ChatPanel。图片/文件经导入器加入正文，
 * 视频和语音仍通过各自的平台回调发送，渲染器只接收已认证的附件模型。
 */
data class ChatMediaConfig(
    /** 当前会话已认证的文件传输 owner。 */
    val fileDownloads: FileDownloadController,
    /** 当前平台的认证图片缩略图渲染器。 */
    val imageContent: @Composable (attachment: Attachment, modifier: Modifier) -> Unit,
    /** 将图片/文件上传进当前 Markdown scope；双端均通过此入口选择和导入资源。 */
    val embeddedAssetImports: EmbeddedAssetImportGateway? = null,
    /** 仅当剪贴板包含二进制/文件资源且已消费粘贴时返回 true。 */
    val onPasteEmbeddedAsset: (() -> Boolean)? = null,
    /** 选择视频发送。null=附件面板不显示视频项。 */
    val onPickVideo: (() -> Unit)? = null,
    /** 打开文档引用选择器（类型化办公对象引用）。null=不显示文档项。 */
    val onPickDocument: (() -> Unit)? = null,
    /** 打开当前群的群文件引用选择器。null 或非群聊=不显示群文件项。 */
    val onPickGroupFile: (() -> Unit)? = null,
    /** 语音录制：true=开始，false=停止发送。 */
    val onVoiceRecord: ((Boolean) -> Unit)? = null,
    /** 进入语音模式；Android 用于在用户真正长按前申请麦克风权限。 */
    val onVoiceModeEntered: (() -> Unit)? = null,
    /** 指针手势取消或录音控件离开组合：停止并丢弃，不得发送残片。 */
    val onVoiceRecordCancel: (() -> Unit)? = null,
    /** 媒体点击处理。null=不可点击。 */
    val onMediaClick: ((message: Message) -> Unit)? = null,
    /** Markdown sidecar 图片点击；必须以消息 scope + assetId 定位，不能只按存储 path。 */
    val onEmbeddedMediaClick: ((message: Message, asset: EmbeddedAsset) -> Unit)? = null,
    /** 富文本 @提及 点击（打开用户资料）。 */
    val onMentionClick: ((uid: String) -> Unit)? = null,
    /** 富文本超链接点击（桌面开浏览器/Android ACTION_VIEW）。 */
    val onUrlClick: ((String) -> Unit)? = null,
)

/** 文档编辑器、预览与修订历史共享的最小已认证媒体面。 */
data class EmbeddedAssetMediaConfig(
    val fileDownloads: FileDownloadController,
    val imageContent: @Composable (attachment: Attachment, modifier: Modifier) -> Unit,
    /** 二进制剪贴板导入；返回 false 时普通文本粘贴交给聚焦的编辑器。 */
    val onPasteEmbeddedAsset: (() -> Boolean)? = null,
)

/** 共享的快捷键约定：只有被消费的二进制粘贴可以阻止原生文本粘贴。 */
internal fun consumeEmbeddedAssetPasteShortcut(
    isKeyDown: Boolean,
    isPasteKey: Boolean,
    hasCommandModifier: Boolean,
    onPasteEmbeddedAsset: (() -> Boolean)?,
): Boolean {
    if (!isKeyDown || !isPasteKey || !hasCommandModifier) return false
    return onPasteEmbeddedAsset?.invoke() == true
}

val LocalEmbeddedAssetMediaConfig = staticCompositionLocalOf<EmbeddedAssetMediaConfig?> { null }

/**
 * 会话级的小尺寸身份图片已认证渲染器。
 *
 * 公共 UI 负责校验与兜底。平台只接收规范的 TeamTalk [Attachment]，并在该兜底之上绘制
 * 成功解码的本地缓存条目。
 */
data class IdentityImageMediaConfig(
    val imageContent: @Composable (attachment: Attachment, modifier: Modifier) -> Unit,
)

val LocalIdentityImageMediaConfig = staticCompositionLocalOf<IdentityImageMediaConfig?> { null }
