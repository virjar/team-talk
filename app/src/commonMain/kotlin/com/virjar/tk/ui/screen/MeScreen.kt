package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.virjar.tk.model.User
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.theme.Tk
import com.virjar.tk.ui.theme.TkTheme
import com.virjar.tk.ui.theme.ThemeMode

/**
 * 设置页 Header 样式。
 * - [Mobile]：中性资料表面 + 64dp 头像 + UID（移动端全宽）
 * - [Compact]：扁平 Surface + 48dp 头像 + 无 UID（桌面窄中栏）
 */
enum class MeHeaderStyle { Mobile, Compact }

@Composable
fun MeScreen(
    currentUser: User?,
    onLogout: () -> Unit,
    onEditProfile: () -> Unit = {},
    onChangePassword: () -> Unit = {},
    onDeviceManagement: () -> Unit = {},
    onBlacklist: () -> Unit = {},
    buildInfoText: String = "",
    headerStyle: MeHeaderStyle = MeHeaderStyle.Mobile,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // ── 用户信息 Header（按 style 切换样式）──
        when (headerStyle) {
            MeHeaderStyle.Mobile -> MobileProfileHeader(currentUser)
            MeHeaderStyle.Compact -> {
                CompactProfileHeader(currentUser)
                HorizontalDivider()
            }
        }

        // ── 设置菜单组（内容两端一致，容器样式按 style 切换）──
        // 外观（主题模式）内联处理，不走平台回调
        var showThemeDialog by remember { mutableStateOf(false) }
        val menuItems = buildList {
            add("编辑资料" to onEditProfile)
            add("修改密码" to onChangePassword)
            add("设备管理" to onDeviceManagement)
            add("黑名单" to onBlacklist)
            add(THEME_ROW to { showThemeDialog = true })
        }
        when (headerStyle) {
            MeHeaderStyle.Mobile -> {
                Spacer(Modifier.height(8.dp))
                // Card 包裹 + ›箭头
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Column {
                        menuItems.forEachIndexed { index, (title, onClick) ->
                            SettingsRow(
                                title, onClick,
                                showChevron = title != THEME_ROW,
                                showDivider = index < menuItems.size - 1,
                                trailing = themeTrailing(title),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                // 退出登录（Card 包裹 + 居中）
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onLogout).testTag("settings.logout").padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("退出登录", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
            MeHeaderStyle.Compact -> {
                // 扁平列表 + HorizontalDivider 分隔
                menuItems.forEachIndexed { index, (title, onClick) ->
                    SettingsRow(
                        title, onClick,
                        showChevron = false,
                        showDivider = true,
                        trailing = themeTrailing(title),
                    )
                }
                HorizontalDivider()
                // 退出登录（扁平行 + 左对齐）
                Box(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onLogout).testTag("settings.logout").padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider()
            }
        }

        // ── 构建信息（两端一致）──
        if (headerStyle == MeHeaderStyle.Compact) {
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.height(16.dp))
        }
        if (buildInfoText.isNotBlank()) {
            Text(
                text = buildInfoText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (headerStyle == MeHeaderStyle.Compact) 12.dp else 0.dp),
            )
        }

        // ── 主题模式选择对话框 ──
        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text("外观") },
                text = {
                    Column {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { TkTheme.set(mode); showThemeDialog = false }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = TkTheme.mode == mode,
                                    onClick = { TkTheme.set(mode); showThemeDialog = false },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
                },
            )
        }
    }
}

private const val THEME_ROW = "外观"

/** 外观行的右侧当前值标签（其余菜单行无 trailing）。 */
@Composable
private fun themeTrailing(title: String): @Composable RowScope.() -> Unit =
    if (title == THEME_ROW) {
        {
            Text(
                TkTheme.mode.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    } else {
        {}
    }

/** 移动端 Header：中性资料表面 + 统一圆角方形头像，避免设置页成为孤立的蓝色大色块。 */
@Composable
private fun MobileProfileHeader(currentUser: User?) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarPlaceholder(
                name = currentUser?.name?.ifBlank { null } ?: currentUser?.username,
                size = 64,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    currentUser?.name?.ifBlank { null } ?: currentUser?.username ?: "未知",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                if (currentUser?.username != null) {
                    Text("@${currentUser.username}", style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
                }
                Text("UID: ${currentUser?.uid?.take(12) ?: ""}", style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
            }
        }
    }
}

/** 桌面端 Header：扁平 Surface + 与其他列表一致的圆角方形头像。 */
@Composable
private fun CompactProfileHeader(currentUser: User?) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarPlaceholder(
                name = currentUser?.name?.ifBlank { null } ?: currentUser?.username,
                size = 48,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    currentUser?.name?.ifBlank { null } ?: currentUser?.username ?: "未知",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                if (currentUser?.username != null) {
                    Text("@${currentUser.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/**
 * 设置菜单行（两端共用）。
 * @param showChevron 是否显示右侧 › 箭头（Mobile 风格）
 * @param showDivider 是否显示底部分隔线
 * @param trailing 右侧槽（外观行显示当前主题）
 */
@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    showDivider: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag("settings.${title}")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            trailing()
            if (showChevron) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
