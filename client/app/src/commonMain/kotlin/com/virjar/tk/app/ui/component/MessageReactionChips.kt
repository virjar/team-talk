package com.virjar.tk.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.app.ui.component.input.CommonEmojiGrid
import com.virjar.tk.app.ui.theme.Tk

/**
 * 表情回应（CLIENT-05）共享交互件。
 *
 * 两层入口（钉钉/飞书范式）：长按/右键菜单的快捷栏承载常用候选，尾部"＋"与已有 chips
 * 行尾的"＋"展开完整选择器。服务端接受策略内的任意 emoji，选择器与输入区表情面板共用
 * 同一候选集，保证跨端体验一致。
 */
val REACTION_QUICK_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** 气泡下方的聚合 chips：emoji + 服务端计数；我已回应的 chip 高亮，点击切换；行尾"＋"展开选择器。 */
@Composable
fun ReactionChips(
    groups: List<MessageReactionGroup>,
    myUid: String,
    onToggle: (emoji: String) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
) {
    if (groups.isEmpty()) return
    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Tk.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEach { group ->
            val mine = myUid.isNotEmpty() && group.reactorUids.contains(myUid)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (mine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (mine) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .testTag("chat.reaction.chip.${group.emoji}")
                    .clickable { onToggle(group.emoji) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(group.emoji, fontSize = 16.sp)
                    Text(
                        "${group.reactorUids.size}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        MoreReactionButton(onMore)
    }
}

/** 长按/右键菜单顶部的快捷回应栏；尾部"＋"展开完整选择器。 */
@Composable
fun ReactionQuickBar(
    onPick: (emoji: String) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        REACTION_QUICK_EMOJIS.forEach { emoji ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .testTag("chat.reaction.quick.$emoji")
                    .clickable { onPick(emoji) },
            ) {
                Text(
                    emoji,
                    fontSize = 24.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        MoreReactionButton(onMore)
    }
}

/**
 * 完整回应选择器：锚定在气泡旁弹出，与输入区表情面板共用候选集。
 *
 * 用 DropdownMenu 承载而不是裸 Popup：Popup 的 alignment 相对窗口根，会把弹层定位到
 * 窗口角落并遮住消息区；DropdownMenu 沿用气泡锚点并在贴近屏幕边缘时自动翻转。
 * 选择器语义是"确保我加了该回应"；取消已有回应走 chips 点击。
 */
@Composable
fun ReactionPickerPopup(
    onPick: (emoji: String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("chat.reaction.picker"),
    ) {
        CommonEmojiGrid(onPick, itemTestTagPrefix = "chat.reaction.pick.")
    }
}

@Composable
private fun MoreReactionButton(onMore: (() -> Unit)?) {
    if (onMore == null) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag("chat.reaction.more")
            .clickable { onMore() },
    ) {
        Text(
            "＋",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
