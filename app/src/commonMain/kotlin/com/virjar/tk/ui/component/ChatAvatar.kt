package com.virjar.tk.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import com.virjar.tk.model.ChatType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 会话头像：圆角方形占位 + 群聊右下角多人角标。
 *
 * 群聊（[ChatType.GROUP]）角标用于和私聊区分；[ChatType.PERSONAL] 维持原样。
 * 真实头像 [url] 预留（后端 updateProfile 支持 avatar 后启用）。
 */
@Composable
fun ChatAvatar(
    chatType: Int,
    chatName: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    url: String? = null,
) {
    val isGroup = ChatType.fromCode(chatType) == ChatType.GROUP

    Box(modifier = modifier.size(size.dp)) {
        AvatarPlaceholder(name = chatName, size = size, url = url)

        // 群聊角标：右下角多人图标
        if (isGroup) {
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
                    imageVector = Icons.Filled.Groups,
                    contentDescription = "群聊",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((badgeSize.value * 0.7f).dp),
                )
            }
        }
    }
}
