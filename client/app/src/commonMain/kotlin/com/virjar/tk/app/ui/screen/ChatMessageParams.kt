package com.virjar.tk.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.component.VoicePlaybackController

/**
 * 单条消息气泡的表情回应交互组。
 *
 * [ChatMessageList] 按消息构造：serverSeq 已绑定进回调，picker 开合状态由列表层
 * `remember(msg.clientMsgId)` 持有。把 chips 数据、本人 uid、快捷栏与完整选择器的
 * 开合回调收敛为一个对象，避免七个同类参数在列表→气泡间逐层透传。
 */
@Immutable
class MessageReactions(
    val groups: List<MessageReactionGroup> = emptyList(),
    val myUid: String = "",
    val onToggle: (emoji: String) -> Unit = {},
    val pickerVisible: Boolean = false,
    val onOpenPicker: () -> Unit = {},
    val onDismissPicker: () -> Unit = {},
    val onPick: (emoji: String) -> Unit = {},
)

/**
 * 聊天级消息正文展示上下文：发送者解析、正文内导航、媒体交互与渲染槽。
 *
 * 由 [ChatPanel] 在会话组装期构造一次并原样下传（列表层与气泡层均不逐条改写）；
 * 承载的是"渲染一条消息正文需要什么"，与单条消息自身的状态无关。
 */
@Immutable
class MessageContentContext(
    val resolveSender: ((uid: String) -> User?)? = null,
    val voicePlayback: VoicePlaybackController,
    val onMentionClick: ((uid: String) -> Unit)? = null,
    val onUrlClick: ((String) -> Unit)? = null,
    val onMediaClick: ((Message) -> Unit)? = null,
    val onEmbeddedMediaClick: ((Message, EmbeddedAsset) -> Unit)? = null,
    val imageContent: @Composable (Attachment, Modifier) -> Unit,
)
