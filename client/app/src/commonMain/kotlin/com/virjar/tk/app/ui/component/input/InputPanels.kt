package com.virjar.tk.app.ui.component.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.app.ui.component.CHAT_ATTACHMENT_FILE_TEST_TAG
import com.virjar.tk.app.ui.component.CHAT_ATTACHMENT_IMAGE_TEST_TAG
import com.virjar.tk.app.ui.component.CHAT_ATTACHMENT_PANEL_TEST_TAG
import com.virjar.tk.app.ui.component.CHAT_ATTACHMENT_PASTE_TEST_TAG
import com.virjar.tk.app.ui.component.CHAT_ATTACHMENT_VIDEO_TEST_TAG
import com.virjar.tk.app.ui.theme.Tk

/**
 * 内联表情面板：作为聊天输入 Column 的普通子项参与布局，展开时把输入行推到面板上方。
 * 常用 emoji 网格，点击插入光标处；自定义表情包与卡片扩展见 doc/05-clients/rich-content.md。
 * 以前的弹层实现会把 260dp 的网格盖在输入行上，用户看不到正在输入的草稿（T002）。
 */
@Composable
fun InlineEmojiPanel(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth().testTag("chat.emoji.panel"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        CommonEmojiGrid(onPick, fillMaxWidth = true)
    }
}

/**
 * 常用 emoji 网格：输入区表情面板与消息回应选择器共用同一候选集与布局。
 *
 * 显式 size 不可省略：Android 的 DropdownMenu 会对内容做 intrinsic 测量，而
 * LazyVerticalGrid（SubcomposeLayout）不支持 intrinsic 查询，缺省尺寸会直接崩溃。
 */
@Composable
internal fun CommonEmojiGrid(
    onPick: (String) -> Unit,
    itemTestTagPrefix: String? = null,
    fillMaxWidth: Boolean = false,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.width(COMMON_EMOJI_GRID_WIDTH))
            .height(260.dp)
            .padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.sm),
    ) {
        // 最近使用（FIFO，最多 5 个）
        val recent = RecentEmojis.emojis
        if (recent.isNotEmpty()) {
            item(key = "recent-header", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "最近使用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            items(recent, key = { "recent_$it" }) { emoji ->
                EmojiCell(emoji, onPick, itemTestTagPrefix)
            }
            item(key = "recent-divider", span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        // 全量候选
        items(CommonEmojis, key = { "all_$it" }) { emoji ->
            EmojiCell(emoji, onPick, itemTestTagPrefix)
        }
    }
}

/** 单个 emoji 格子：28dp 字号填满 40dp 格子，点击后记录到最近使用。 */
@Composable
private fun EmojiCell(
    emoji: String,
    onPick: (String) -> Unit,
    itemTestTagPrefix: String?,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .let { base ->
                if (itemTestTagPrefix != null) base.testTag("$itemTestTagPrefix$emoji") else base
            }
            .clickable {
                RecentEmojis.record(emoji)
                onPick(emoji)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 26.sp)
    }
}

/** 附件面板（＋弹层）：图片/视频/文件 宫格（飞书范式，替代 AlertDialog 文字列表）。 */
@Composable
fun AttachmentPanel(
    onPickImage: () -> Unit,
    onPickVideo: (() -> Unit)?,
    onPickFile: () -> Unit,
    onPasteAsset: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPickDocument: (() -> Unit)? = null,
    onPickGroupFile: (() -> Unit)? = null,
) {
    val actions = buildList {
        add(AttachmentPanelAction(Icons.Filled.Image, "图片", CHAT_ATTACHMENT_IMAGE_TEST_TAG, onPickImage))
        onPickVideo?.let {
            add(AttachmentPanelAction(Icons.Filled.OndemandVideo, "视频", CHAT_ATTACHMENT_VIDEO_TEST_TAG, it))
        }
        onPickDocument?.let {
            add(AttachmentPanelAction(Icons.AutoMirrored.Filled.InsertDriveFile, "文档", "chat.attach.document", it))
        }
        onPickGroupFile?.let {
            add(AttachmentPanelAction(Icons.AutoMirrored.Filled.InsertDriveFile, "群文件", "chat.attach.groupfile", it))
        }
        add(
            AttachmentPanelAction(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                "文件",
                CHAT_ATTACHMENT_FILE_TEST_TAG,
                onPickFile,
            ),
        )
        onPasteAsset?.let {
            add(AttachmentPanelAction(Icons.Filled.ContentPaste, "粘贴", CHAT_ATTACHMENT_PASTE_TEST_TAG, it))
        }
    }
    InputPopupSurface(
        modifier = modifier.testTag(CHAT_ATTACHMENT_PANEL_TEST_TAG),
        onDismiss = onDismiss,
    ) {
        BoxWithConstraints {
            // 每项至少 56dp：窄屏下超出可用宽度时改两列排布，绝不挤压末项让图标残缺、
            // 标签逐字换行（T014）；更宽的面板保持单行并支持横向滑动兜底。
            val itemWidth = 56.dp
            val itemSpacing = Tk.spacing.sm
            val singleRowWidth = itemWidth * actions.size + itemSpacing * (actions.size - 1)
            if (actions.size > 2 && maxWidth < singleRowWidth) {
                Column(
                    modifier = Modifier.padding(Tk.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Tk.spacing.xs),
                ) {
                    actions.chunked(2).forEach { rowActions ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.lg)) {
                            rowActions.forEach { action -> AttachmentItem(action) }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .padding(Tk.spacing.md)
                        .then(
                            if (singleRowWidth > maxWidth) {
                                Modifier.horizontalScroll(rememberScrollState())
                            } else {
                                Modifier
                            },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    actions.forEach { action -> AttachmentItem(action, itemWidth) }
                }
            }
        }
    }
}

private data class AttachmentPanelAction(
    val icon: ImageVector,
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
)

@Composable
private fun AttachmentItem(action: AttachmentPanelAction, itemWidth: Dp = 64.dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(itemWidth)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = action.onClick)
            .testTag(action.testTag)
            .padding(vertical = Tk.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = action.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(Tk.spacing.xs))
        Text(action.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
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

/** 8 列 × 36dp 项宽 + 左右边距，与输入区表情面板一致的固定网格宽度。 */
internal val COMMON_EMOJI_GRID_WIDTH = (40.dp * 8) + (Tk.spacing.sm * 2) + 8.dp

/** 常用 emoji 集（8 列网格，24 行 × 8 = 192 容量）。 */
internal val CommonEmojis = listOf(
    // ── 高频 ──
    "👍","❤️","😂","😊","🎉","🙏","😮","😢",
    "🔥","👏","😍","🤔","💪","✅","😎","🥰",
    // ── 笑脸 ──
    "😀","😃","😄","😁","😆","😅","🤣","🙃",
    "🙂","😉","😇","😘","😗","😚","😙","🥲",
    "😋","😛","😜","🤪","😝","🤑","🤗","🤭",
    "🤫","🤐","😐","😑","😶","😏","😒","🙄",
    "😬","🤥","😌","😔","😪","🤤","😴","😷",
    "🤒","🤕","🤢","🤮","🥵","🥶","😵","🤯",
    "🤠","🥳","🤓","🧐","😕","😟","🙁","😯",
    "😲","😳","🥺","😦","😧","😨","😰","😥",
    "😢","😭","😱","😖","😣","😞","😓","😩",
    // ── 手势 ──
    "👎","🤝","👊","✌️","🤞","🤟","🤘","👌",
    "🤙","👋","🖐️","✋",
    // ── 心形 ──
    "🧡","💛","💚","💙","💜","🖤","🤍",
    "💔","❣️","💕","💞","💓","💗","💖","💘",
    // ── 符号 ──
    "🎊","🎯","🏆","🥇","❌","⚡","💧","⭐",
    "🌟","✨","🌈","☀️","🌙",
)
