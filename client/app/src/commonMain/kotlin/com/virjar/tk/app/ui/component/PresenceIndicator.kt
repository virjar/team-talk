package com.virjar.tk.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.shared.client.FriendPresence
import com.virjar.tk.shared.client.FriendPresenceStatus

/** 在线角标的展示载荷：仅 ONLINE 产出，未知/离线不画任何标记。 */
data class PresenceIndicatorPresentation(
    val testTag: String,
    val contentDescription: String,
)

/**
 * 在线状态展示策略：服务端 presence 快照/事件仅对好友发放，未知（未快照/断线）
 * 与离线一律不展示——避免把 UNKNOWN 误读成"离线"。
 */
fun presenceIndicatorPresentation(
    friendUid: String,
    presence: FriendPresence?,
): PresenceIndicatorPresentation? {
    if (presence?.status != FriendPresenceStatus.ONLINE) return null
    return PresenceIndicatorPresentation(
        testTag = "contact.presence.${friendUid.take(8)}",
        contentDescription = "在线",
    )
}

/** 头像右下角 ONLINE 圆点；非在线（含未知）不渲染任何内容。 */
@Composable
fun PresenceBadge(
    friendUid: String,
    presence: FriendPresence?,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val indicator = presenceIndicatorPresentation(friendUid, presence) ?: return
    Surface(
        modifier = modifier
            .size(size)
            .testTag(indicator.testTag)
            .semantics { contentDescription = indicator.contentDescription },
        shape = CircleShape,
        color = Tk.colors.online,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface),
        content = {},
    )
}

/** 会话头部/资料页的"在线"文字标签（绿点 + 文案）；非在线不渲染。 */
@Composable
fun PresenceLabel(
    friendUid: String,
    presence: FriendPresence?,
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp,
    contentColor: Color = Tk.colors.online,
) {
    val indicator = presenceIndicatorPresentation(friendUid, presence) ?: return
    Row(
        modifier = modifier
            .testTag(indicator.testTag)
            .semantics { contentDescription = indicator.contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(dotSize),
            shape = CircleShape,
            color = Tk.colors.online,
        ) {}
        Text(
            "在线",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
