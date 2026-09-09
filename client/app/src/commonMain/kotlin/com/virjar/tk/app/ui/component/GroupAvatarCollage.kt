package com.virjar.tk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.bridge.LocalIdentityImageMediaConfig
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.User

/** 群头像拼图最多取用的成员头像数（2×2）。 */
const val GROUP_AVATAR_CELL_LIMIT = 4

/**
 * 拼图取格：按成员次序去重（同 uid 只取一次），仅保留已带用户投影的成员，取前
 * [limit] 个。冷启动时成员可能还没有嵌入 user 投影——跳过而不是渲染问号占位，
 * 投影收敛后头像自然补齐。
 */
fun groupAvatarCellUsers(members: List<Member>, limit: Int = GROUP_AVATAR_CELL_LIMIT): List<User> =
    members.asSequence()
        .mapNotNull { it.user }
        .distinctBy { it.uid }
        .take(limit)
        .toList()

/**
 * 群头像拼图：1 人全幅，2 人上下对分，3 人上 1 下 2，≥4 人 2×2。
 *
 * 每格复用个人头像的占位/认证图片渲染链路（首字母 + 家族色 + canonical 校验），
 * 空槽显示群名派生的底色；外层 squircle 裁剪与 [ChatAvatar] 的角标叠加保持一致。
 */
@Composable
fun GroupAvatarCollage(
    chatName: String?,
    members: List<User>,
    modifier: Modifier = Modifier,
    size: Int = 48,
) {
    val cells = members.take(GROUP_AVATAR_CELL_LIMIT)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val emptyStyle = avatarFallbackStyle(chatName, darkTheme)

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(Tk.avatarShape(size.dp))
            .background(emptyStyle.background),
    ) {
        when (cells.size) {
            0 -> Unit
            1 -> GroupAvatarCell(cells[0], Modifier.fillMaxSize())
            else -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                val rows = if (cells.size == 2) listOf(cells) else listOf(cells.take(2), cells.drop(2))
                rows.forEach { rowCells ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        if (cells.size == 2) {
                            GroupAvatarCell(rowCells[0], Modifier.weight(1f).fillMaxHeight())
                            GroupAvatarCell(rowCells[1], Modifier.weight(1f).fillMaxHeight())
                        } else {
                            repeat(2) { column ->
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                    rowCells.getOrNull(column)?.let { user ->
                                        GroupAvatarCell(user, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupAvatarCell(user: User, modifier: Modifier = Modifier) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fallbackStyle = avatarFallbackStyle(user.name, darkTheme)
    Box(
        modifier = modifier.background(fallbackStyle.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = firstDisplayChar(user.name),
            style = MaterialTheme.typography.labelLarge,
            color = fallbackStyle.foreground,
            fontWeight = FontWeight.SemiBold,
        )
        acceptedAvatarAttachmentOrNull(user.avatar)?.let { accepted ->
            LocalIdentityImageMediaConfig.current?.imageContent?.invoke(
                accepted,
                Modifier.fillMaxSize(),
            )
        }
    }
}
