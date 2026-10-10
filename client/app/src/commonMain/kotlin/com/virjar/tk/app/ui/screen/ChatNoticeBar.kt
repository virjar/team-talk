package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.theme.Tk

/**
 * 群聊顶部公告条（MSG-01/F2，纯客户端呈现）：管理员在群设置维护的群公告
 * （`Chat.notice`，服务端权威、CHAT_UPDATED 同步）在聊天界面的常驻入口。
 * 摘要一行；点击弹出全文；关闭只影响当前会话组合（公告是群事实，再次进入重新显示）。
 */
@Composable
internal fun ChatNoticeBar(
    notice: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = notice.lines().firstOrNull { it.isNotBlank() } ?: notice
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs)
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .testTag("chat.notice.bar")
            .padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Campaign,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Tk.dimens.iconSize),
        )
        Text(
            "公告",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Tk.spacing.xs),
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Tk.spacing.sm),
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = "关闭群公告条",
            tint = Tk.colors.secondaryText,
            modifier = Modifier
                .size(Tk.dimens.iconSize)
                .clickable(onClick = onDismiss)
                .testTag("chat.notice.dismiss"),
        )
    }
}
