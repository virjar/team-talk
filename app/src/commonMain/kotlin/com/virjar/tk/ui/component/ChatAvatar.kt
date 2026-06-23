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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 会话头像。
 *
 * 当前未实现真实头像图片加载，统一使用「首字母 + 哈希随机色」占位
 * （由 [AvatarPlaceholder] 提供）。群聊（[ChatType.GROUP]）在右下角叠加多人图标角标，
 * 用于和私聊区分。[ChatType.PERSONAL] 维持原样。
 */
@Composable
fun ChatAvatar(
    chatType: Int,
    chatName: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
) {
    val isGroup = ChatType.fromCode(chatType) == ChatType.GROUP

    Box(modifier = modifier.size(size.dp)) {
        AvatarPlaceholder(name = chatName, size = size)

        // 群聊角标：右下角多人图标
        if (isGroup) {
            val badgeSize = (size * 0.45f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Groups,
                    contentDescription = "群聊",
                    tint = Color(0xFF3370FF),
                    modifier = Modifier.size((badgeSize.value * 0.7f).dp),
                )
            }
        }
    }
}
