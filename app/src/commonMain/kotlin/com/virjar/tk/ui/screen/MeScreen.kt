package com.virjar.tk.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.virjar.tk.model.User

/**
 * 设置页 Header 样式。
 * - [Mobile]：渐变背景 + 64dp 头像 + 白字 + UID（适合移动端全宽展示）
 * - [Compact]：抬升 Surface + 48dp 头像 + 普通字色 + 无 UID（适合桌面窄中栏）
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
        val menuItems = listOf(
            "编辑资料" to onEditProfile,
            "修改密码" to onChangePassword,
            "设备管理" to onDeviceManagement,
            "黑名单" to onBlacklist,
        )
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
                            SettingsRow(title, onClick, showChevron = true, showDivider = index < menuItems.size - 1)
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
                    SettingsRow(title, onClick, showChevron = false, showDivider = true)
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
    }
}

/** 移动端 Header：渐变背景 + 64dp 白色半透头像 + 白字 + UID。 */
@Composable
private fun MobileProfileHeader(currentUser: User?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                )
            )
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    currentUser?.name?.take(1) ?: currentUser?.username?.take(1) ?: "?",
                    style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    currentUser?.name?.ifBlank { null } ?: currentUser?.username ?: "未知",
                    style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold,
                )
                if (currentUser?.username != null) {
                    Text("@${currentUser.username}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                }
                Text("UID: ${currentUser?.uid?.take(12) ?: ""}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

/** 桌面端 Header：抬升 Surface + 48dp primaryContainer 头像 + 普通字色 + 无 UID。 */
@Composable
private fun CompactProfileHeader(currentUser: User?) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    currentUser?.name?.take(1) ?: currentUser?.username?.take(1) ?: "?",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold,
                )
            }
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
 */
@Composable
private fun SettingsRow(title: String, onClick: () -> Unit, showChevron: Boolean = true, showDivider: Boolean = true) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag("settings.${title}")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (showChevron) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
