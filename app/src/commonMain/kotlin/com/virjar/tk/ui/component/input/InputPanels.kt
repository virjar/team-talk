package com.virjar.tk.ui.component.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.Tk

/**
 * 表情面板（输入区弹层）。常用 emoji 网格，点击插入光标处。
 * 内置常用集；自定义表情包与卡片扩展见 doc/05-clients/rich-content.md。
 */
@Composable
fun EmojiPanel(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InputPopupSurface(modifier = modifier, onDismiss = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.height(220.dp).padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.sm),
        ) {
            items(CommonEmojis) { emoji ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** 附件面板（＋弹层）：图片/视频/文件 宫格（飞书范式，替代 AlertDialog 文字列表）。 */
@Composable
fun AttachmentPanel(
    onPickImage: () -> Unit,
    onPickVideo: (() -> Unit)?,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InputPopupSurface(modifier = modifier, onDismiss = onDismiss) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(Tk.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Tk.spacing.lg),
        ) {
            AttachmentItem(Icons.Filled.Image, "图片", onPickImage)
            if (onPickVideo != null) AttachmentItem(Icons.Filled.OndemandVideo, "视频", onPickVideo)
            AttachmentItem(Icons.AutoMirrored.Filled.InsertDriveFile, "文件", onPickFile)
        }
    }
}

@Composable
private fun AttachmentItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Tk.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(Tk.spacing.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

/** 输入区弹层底板：白底、圆角、边框、外点关闭。 */
@Composable
internal fun InputPopupSurface(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // focusable=true：点击外部/ESC 触发 onDismissRequest（false 时不触发，弹层关不掉）
    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomStart,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        androidx.compose.material3.Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            content()
        }
    }
}

/** 常用 emoji 集（8 列网格，24 行 × 8 = 192 容量）。 */
internal val CommonEmojis = listOf(
    "😀","😃","😄","😁","😆","😅","🤣","😂",
    "🙂","🙃","😉","😊","😇","🥰","😍","🤩",
    "😘","😗","😚","😙","🥲","😋","😛","😜",
    "🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐",
    "😐","😑","😶","😏","😒","🙄","😬","🤥",
    "😌","😔","😪","🤤","😴","😷","🤒","🤕",
    "🤢","🤮","🥵","🥶","😵","🤯","🤠","🥳",
    "😎","🤓","🧐","😕","😟","🙁","😮","😯",
    "😲","😳","🥺","😦","😧","😨","😰","😥",
    "😢","😭","😱","😖","😣","😞","😓","😩",
    "👍","👎","👏","🙏","🤝","💪","👊","✌️",
    "🤞","🤟","🤘","👌","🤙","👋","🖐️","✋",
    "❤️","🧡","💛","💚","💙","💜","🖤","🤍",
    "💔","❣️","💕","💞","💓","💗","💖","💘",
    "🎉","🎊","🎯","🏆","🥇","✅","❌","⚡",
    "🔥","💧","⭐","🌟","✨","🌈","☀️","🌙",
)
