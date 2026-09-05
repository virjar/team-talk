package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
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
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.SettingsDangerAction
import com.virjar.tk.app.ui.component.SettingsEntryRow
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.component.SettingsSectionLabel
import com.virjar.tk.app.ui.component.ThemeSegmentedSelector
import com.virjar.tk.app.ui.theme.Tk

/**
 * Android 个人设置页（移动端全屏）。Desktop 使用独立的居中设置模态
 * （client/desktop DesktopSettingsDialog），但共享同一套设置零件组件。
 */
@Composable
fun MeScreen(
    currentUser: User?,
    onLogout: () -> Unit,
    onEditProfile: () -> Unit = {},
    onChangePassword: () -> Unit = {},
    onDeviceManagement: () -> Unit = {},
    onBlacklist: () -> Unit = {},
    buildInfoText: String = "",
    modifier: Modifier = Modifier,
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // ── 用户信息 Header：中性资料表面 + 64dp 头像 + UID ──
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarPlaceholder(
                    name = currentUser?.name?.ifBlank { null } ?: currentUser?.username,
                    avatar = currentUser?.avatar,
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
                    val meta = buildString {
                        currentUser?.username?.let { append("@$it") }
                        currentUser?.uid?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append("UID ${it.take(12)}")
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))

            SettingsSectionLabel("账号")
            SettingsGroupCard {
                SettingsEntryRow(
                    icon = Icons.Filled.Person,
                    title = "编辑资料",
                    description = "设置头像、显示名与手机号",
                    onClick = onEditProfile,
                    tag = "settings.编辑资料",
                )
                SettingsEntryRow(
                    icon = Icons.Filled.Lock,
                    title = "修改密码",
                    description = "定期更换密码，保护账号安全",
                    onClick = onChangePassword,
                    tag = "settings.修改密码",
                )
            }

            Spacer(Modifier.height(10.dp))
            SettingsSectionLabel("安全")
            SettingsGroupCard {
                SettingsEntryRow(
                    icon = Icons.Filled.Devices,
                    title = "设备管理",
                    description = "查看登录设备，可远程下线",
                    onClick = onDeviceManagement,
                    tag = "settings.设备管理",
                )
                SettingsEntryRow(
                    icon = Icons.Filled.Block,
                    title = "黑名单",
                    description = "管理已屏蔽的联系人",
                    onClick = onBlacklist,
                    tag = "settings.黑名单",
                )
            }

            Spacer(Modifier.height(10.dp))
            SettingsSectionLabel("通用")
            SettingsGroupCard {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Palette,
                            contentDescription = null,
                            tint = Tk.colors.secondaryText,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("外观", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(1.dp))
                            Text("界面明暗", style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ThemeSegmentedSelector(modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingsDangerAction(
                text = "退出登录",
                icon = Icons.Filled.Logout,
                onClick = { showLogoutConfirm = true },
                tag = "settings.logout",
            )

            if (buildInfoText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = buildInfoText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Tk.colors.metaText,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？退出后需要重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                    modifier = Modifier.testTag("settings.logout.confirm"),
                ) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutConfirm = false },
                    modifier = Modifier.testTag("settings.logout.cancel"),
                ) { Text("取消") }
            },
        )
    }
}
