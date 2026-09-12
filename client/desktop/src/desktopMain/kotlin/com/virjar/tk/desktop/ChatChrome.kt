package com.virjar.tk.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.TkNavIcons
import com.virjar.tk.app.ui.component.UnreadBadge
import com.virjar.tk.app.ui.theme.Tk

/**
 * 一级导航栏（148dp，横向"图标+文字"行，钉钉/飞书桌面范式）。规格：doc/05-clients/desktop.md。
 *
 * 顶部：用户头像（点击打开个人设置模态）；中部：会话/通讯录/文档/任务；底部：设置入口。
 * 设置是居中模态面板（飞书/钉钉桌面范式），不是一级常驻栏目；选中态只用于会话/通讯录/文档/任务。
 * 选中项：secondaryContainer 圆角底 + 主色实面图标 + 加粗标签；未选 = 描边图标 + 次级文字。
 * 图标是一级导航专属矢量（TkNavIcons）——通用图标里会话/文档/任务剪影雷同，辨识度差（内测反馈）。
 */
@Composable
internal fun SlimNavRail(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    pendingApplyCount: Int,
    currentUserName: String?,
    currentUserAvatar: Attachment?,
) {
    Surface(
        modifier = Modifier.width(Tk.dimens.railWidth).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = Tk.spacing.sm, horizontal = Tk.spacing.xs),
        ) {
            // 用户头像 → 个人设置模态（兼作头像入口）
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onOpenSettings) {
                    AvatarPlaceholder(
                        name = currentUserName,
                        avatar = currentUserAvatar,
                        size = 32,
                        modifier = Modifier.testTag("nav.avatar"),
                    )
                }
            }

            Spacer(Modifier.height(Tk.spacing.sm))

            // 会话
            RailItem(
                filledIcon = TkNavIcons.ChatsFilled,
                outlinedIcon = TkNavIcons.ChatsOutlined,
                label = MainTab.CONVERSATIONS.label,
                selected = selectedTab == MainTab.CONVERSATIONS.ordinal,
                onClick = { onSelectTab(MainTab.CONVERSATIONS.ordinal) },
            )

            // 通讯录（好友申请红点）
            RailItem(
                filledIcon = TkNavIcons.ContactsFilled,
                outlinedIcon = TkNavIcons.ContactsOutlined,
                badgeCount = pendingApplyCount,
                label = MainTab.CONTACTS.label,
                selected = selectedTab == MainTab.CONTACTS.ordinal,
                onClick = { onSelectTab(MainTab.CONTACTS.ordinal) },
            )

            RailItem(
                filledIcon = TkNavIcons.DocumentsFilled,
                outlinedIcon = TkNavIcons.DocumentsOutlined,
                label = MainTab.DOCUMENTS.label,
                selected = selectedTab == MainTab.DOCUMENTS.ordinal,
                onClick = { onSelectTab(MainTab.DOCUMENTS.ordinal) },
            )

            RailItem(
                filledIcon = TkNavIcons.TasksFilled,
                outlinedIcon = TkNavIcons.TasksOutlined,
                label = MainTab.TASKS.label,
                selected = selectedTab == MainTab.TASKS.ordinal,
                onClick = { onSelectTab(MainTab.TASKS.ordinal) },
            )

            Spacer(Modifier.weight(1f))

            // 设置（底部对齐；打开模态，不切换一级栏目）
            RailItem(
                filledIcon = Icons.Filled.Settings,
                outlinedIcon = Icons.Outlined.Settings,
                label = MainTab.SETTINGS.label,
                selected = false,
                onClick = onOpenSettings,
            )
        }
    }
}

/** 导航行：40dp 高，横向图标+文字。选中 = secondaryContainer 圆角底 + 主色实面图标 + 加粗标签。 */
@Composable
private fun RailItem(
    filledIcon: ImageVector,
    outlinedIcon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tk.spacing.xs)
            .height(40.dp)
            .hoverable(hoverInteraction)
            .clickable(onClick = onClick)
            .testTag("nav.tab.$label"),
        contentAlignment = Alignment.CenterStart,
    ) {
        // hover 底与选中底共用同一圆角容器
        if (selected || hovered) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.secondaryContainer
                            else -> Tk.colors.hover
                        },
                    ),
            )
        }
        Row(
            // 12dp：116dp 栏宽下仍容纳三字标签不截断（内测反馈 T054）
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (selected) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
            ) {
                val icon = if (selected) filledIcon else outlinedIcon
                if (badgeCount > 0) {
                    BadgedBox(badge = { UnreadBadge(badgeCount) }) {
                        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
                maxLines = 1,
            )
        }
    }
}

/**
 * 列表/面板头 —— 飞书/Slack 桌面范式：标题 + 右侧操作槽 + 底部分隔线。
 * 中栏列表头和聊天面板头共用此组件（消除两段近乎重复的 Surface+Row+Divider 模板）。
 *
 * @param title 标题文字
 * @param onTitleClick 可选标题点击回调；业务动作优先放在 [actions]，避免隐藏入口
 * @param actions 右侧操作槽（图标按钮等）
 */
@Composable
internal fun ListHeader(
    title: String,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Tk.dimens.headerHeight)
                .padding(horizontal = Tk.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
            )
            actions()
        }
        HorizontalDivider(color = Tk.colors.divider)
    }
}
