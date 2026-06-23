package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Member
import com.virjar.tk.ui.component.AvatarPlaceholder

/** 群成员角色：0=普通, 1=管理员, 2=群主。 */
internal fun Member.roleName(): String? = when (role) {
    2 -> "群主"
    1 -> "管理员"
    else -> null
}

/** 成员展示名：优先 user.name，其次 username，再 fallback nickname/uid。 */
internal fun Member.displayName(): String =
    user?.name?.takeIf { it.isNotBlank() }
        ?: user?.username?.takeIf { it.isNotBlank() }
        ?: nickname?.takeIf { it.isNotBlank() }
        ?: uid.take(8)

/** 单行群成员（群详情页 / 群成员列表页共用）。由调用方传入 modifier 控制交互行为。 */
@Composable
internal fun MemberRow(member: Member, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 设了群昵称且与显示名不同时，标注群昵称
                member.nickname?.takeIf { it.isNotBlank() && it != member.user?.name }
                    ?.let { nick ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "群昵称: $nick",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                // 角色标签
                member.roleName()?.let { role ->
                    Spacer(Modifier.width(6.dp))
                    RoleChip(role)
                }
            }
        },
        supportingContent = member.user?.username?.let { uname ->
            {
                Text(
                    "@$uname",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            AvatarPlaceholder(name = member.displayName(), size = 40)
        },
        modifier = modifier.testTag("member.${member.uid.take(8)}"),
    )
}

@Composable
internal fun RoleChip(role: String) {
    val (container, content) = when (role) {
        "群主" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            role,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
