package com.virjar.tk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.virjar.tk.navigation.MainTab
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.UnreadBadge
import com.virjar.tk.ui.theme.Tk

/**
 * 细导航栏（56dp，图标式）。规格：doc/05-clients/desktop.md。
 *
 * 顶部：用户头像（点击进设置）；中部：会话/通讯录；底部：设置。
 * 选中项：图标主色 + 左侧 3dp 蓝色竖条。
 */
@Composable
internal fun SlimNavRail(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    pendingApplyCount: Int,
    currentUserName: String?,
) {
    Surface(
        modifier = Modifier.width(Tk.dimens.railWidth).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = Tk.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 用户头像 → 设置（兼作头像入口）
            Box {
                IconButton(onClick = { onSelectTab(MainTab.SETTINGS.ordinal) }) {
                    AvatarPlaceholder(
                        name = currentUserName,
                        size = 32,
                        modifier = Modifier.testTag("nav.avatar"),
                    )
                }
            }

            Spacer(Modifier.height(Tk.spacing.sm))

            // 会话
            RailItem(
                icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = MainTab.CONVERSATIONS.label) },
                label = MainTab.CONVERSATIONS.label,
                selected = selectedTab == MainTab.CONVERSATIONS.ordinal,
                onClick = { onSelectTab(MainTab.CONVERSATIONS.ordinal) },
            )

            // 通讯录（好友申请红点）
            RailItem(
                icon = {
                    if (pendingApplyCount > 0) {
                        BadgedBox(badge = { UnreadBadge(pendingApplyCount) }) {
                            Icon(Icons.Filled.Contacts, contentDescription = MainTab.CONTACTS.label)
                        }
                    } else {
                        Icon(Icons.Filled.Contacts, contentDescription = MainTab.CONTACTS.label)
                    }
                },
                label = MainTab.CONTACTS.label,
                selected = selectedTab == MainTab.CONTACTS.ordinal,
                onClick = { onSelectTab(MainTab.CONTACTS.ordinal) },
            )

            RailItem(
                icon = { Icon(Icons.Filled.Description, contentDescription = MainTab.DOCUMENTS.label) },
                label = MainTab.DOCUMENTS.label,
                selected = selectedTab == MainTab.DOCUMENTS.ordinal,
                onClick = { onSelectTab(MainTab.DOCUMENTS.ordinal) },
            )

            Spacer(Modifier.weight(1f))

            // 设置（底部对齐）
            RailItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = MainTab.SETTINGS.label) },
                label = MainTab.SETTINGS.label,
                selected = selectedTab == MainTab.SETTINGS.ordinal,
                onClick = { onSelectTab(MainTab.SETTINGS.ordinal) },
            )
        }
    }
}

/** 导航栏单项：48dp 高，选中 = 主色图标 + 左侧 3dp 竖条；hover = 灰底圆角。 */
@Composable
private fun RailItem(
    icon: @Composable () -> Unit,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .hoverable(hoverInteraction)
            .clickable(onClick = onClick)
            .testTag("nav.tab.$label"),
        contentAlignment = Alignment.Center,
    ) {
        // hover 底
        if (hovered && !selected) {
            Box(
                modifier = Modifier
                    .padding(horizontal = Tk.spacing.sm)
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.small)
                    .background(Tk.colors.hover),
            )
        }
        // 选中态：左侧 3dp 竖条
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        CompositionLocalProvider(
            LocalContentColor provides if (selected) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
        ) {
            icon()
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
