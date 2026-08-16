package com.virjar.tk.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.virjar.tk.ui.component.rich.MarkdownText

/**
 * 富文本消息渲染——markdown 渲染的**单点封装**（自研渲染层，见 [rich.MarkdownText]）。
 * 普通文本无 markdown 语法时视觉等同纯文本；含语法时按 markdown 渲染（Discord 语义）。
 * 选型与协议演进：doc/10-rich-messaging/README.md。
 */
@Composable
fun RichMessageText(
    content: String,
    modifier: Modifier = Modifier,
    onUrlClick: ((String) -> Unit)? = null,
    onMentionClick: ((uid: String) -> Unit)? = null,
) {
    MarkdownText(content, modifier, onUrlClick, onMentionClick)
}
