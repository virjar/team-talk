package com.virjar.tk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Bookmark
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Attachment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 会话头像：圆角方形占位，群聊与保存的消息分别显示多人/书签角标。
 *
 * 角标表达会话类型；保存的消息是个人系统服务，不伪造一个对端用户。
 * Chat.avatar 仍是旧字符串投影，不把它当作可下载身份资产；群聊和未解析私聊均显示占位。
 */
@Composable
fun ChatAvatar(
    chatType: Int,
    chatName: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    avatar: Attachment? = null,
) {
    val type = ChatType.fromCode(chatType)
    val badge = when (type) {
        ChatType.GROUP -> Icons.Filled.Groups to "群聊"
        ChatType.SAVED -> Icons.Filled.Bookmark to "系统服务：保存的消息"
        ChatType.PERSONAL -> null
    }

    Box(modifier = modifier.size(size.dp)) {
        AvatarPlaceholder(
            name = chatName,
            size = size,
            avatar = avatar.takeIf { type == ChatType.PERSONAL },
        )

        if (badge != null) {
            val badgeSize = (size * 0.45f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = badge.first,
                    contentDescription = badge.second,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((badgeSize.value * 0.7f).dp),
                )
            }
        }
    }
}
