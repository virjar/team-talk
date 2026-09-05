package com.virjar.tk.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virjar.tk.app.navigation.ScreenDataKey
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.SettingsDangerAction
import com.virjar.tk.app.ui.component.SettingsEntryRow
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.component.SettingsIconButton
import com.virjar.tk.app.ui.component.SettingsSectionLabel
import com.virjar.tk.app.ui.component.ThemeSegmentedSelector
import com.virjar.tk.app.ui.screen.BlockedUser
import com.virjar.tk.app.ui.screen.BlacklistScreen
import com.virjar.tk.app.ui.screen.ChangePasswordScreen
import com.virjar.tk.app.ui.screen.DeviceInfo
import com.virjar.tk.app.ui.screen.DeviceManagementScreen
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.ui.theme.ThemeMode
import com.virjar.tk.app.ui.theme.TkTheme
import com.virjar.tk.desktop.media.DesktopSessionResources

/** 设置模态内部子视图；子流程在同一个模态内切换，不进入 DesktopNav 页面栈。 */
private enum class SettingsView(val page: ClientUiPage) {
    Menu(ClientUiPage.SETTINGS),
    EditProfile(ClientUiPage.EDIT_PROFILE),
    ChangePassword(ClientUiPage.CHANGE_PASSWORD),
    Devices(ClientUiPage.DEVICES),
    Blacklist(ClientUiPage.BLACKLIST),
}

/**
 * Desktop 个人设置：归属主窗口的居中模态面板（飞书/钉钉桌面范式），不占用中栏与右栏。
 *
 * 与用户资料模态同为无装饰 common Dialog：不产生第二套原生标题栏，遮罩、X、ESC 关闭。
 * 菜单视图提供大头像 + 分组卡片（图标 + 标题 + 描述）+ 内联外观分段选择 + 退出确认；
 * 编辑资料、修改密码、设备管理、黑名单作为模态内子视图复用共享屏幕，返回箭头回到菜单。
 */
@Composable
internal fun DesktopSettingsDialog(
    nav: DesktopNav,
    ownerWindow: java.awt.Window,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    buildInfoText: String,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!presentationGate.isOpen || !nav.acceptsRendering) return

    var view by remember { mutableStateOf(SettingsView.Menu) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // 子视图需要的数据与任务窗口时代一致：进入前预载同一 ScreenDataKey。
    LaunchedEffect(view) {
        nav.runAdmittedUiAction(presentationGate, onClosed = {}) {
            when (view) {
                SettingsView.Devices -> nav.loadScreenDataByKey(ScreenDataKey.Devices)
                SettingsView.Blacklist -> nav.loadScreenDataByKey(ScreenDataKey.Blacklist)
                else -> Unit
            }
        }
    }

    Dialog(
        onDismissRequest = presentationGate.guard(onDismiss),
        // 外部点击关闭由自身的 dismissArea 承载（仅菜单视图）：Dialog 默认的
        // dismissOnClickOutside 会绕过子视图的防误触丢稿守卫。
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        if (!presentationGate.isOpen || !nav.acceptsRendering) return@Dialog
        DesktopOwnedModalTelemetry(
            ownerWindow = ownerWindow,
            page = view.page,
            telemetry = nav.telemetry,
            disposalExitReason = {
                desktopWindowDisposalExitReason(presentationGate.isOpen)
            },
        )

        Box(modifier = Modifier.fillMaxSize().testTag("settings.overlay")) {
            // 子视图携带表单草稿，遮罩点击只在菜单视图关闭，避免误触丢稿。
            if (view == SettingsView.Menu) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .testTag("settings.dismissArea")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = presentationGate.guard(onDismiss),
                        ),
                )
            }

            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .width(480.dp)
                        .then(view.dialogHeightModifier())
                        // 参与命中测试但不添加虚假点击动作：子组件先消费手势，空白卡片区域
                        // 在这里被消费，不会穿透到下方的关闭层。
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                                        if (!change.isConsumed) change.consume()
                                    }
                                }
                            }
                        }
                        .testTag("settings.dialog"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.background,
                    // 不用 tonalElevation：绝对色调高度会累积到内层卡片 Surface，给白色卡片
                    // 叠加主色调；层级感由 background/surface 两层表面色与阴影承担。
                    shadowElevation = 16.dp,
                ) {
                    when (view) {
                        SettingsView.Menu -> SettingsMenuView(
                            nav = nav,
                            buildInfoText = buildInfoText,
                            onOpenView = presentationGate.guard { target: SettingsView -> view = target },
                            onLogoutRequest = presentationGate.guard { showLogoutConfirm = true },
                            onClose = presentationGate.guard(onDismiss),
                        )

                        SettingsView.EditProfile -> DesktopEditProfileHost(
                            currentUser = nav.account.currentUser,
                            resources = resources,
                            onSave = { name, phone, avatar ->
                                nav.runAdmittedUiAction(presentationGate, onClosed = { false }) {
                                    nav.account.saveProfile(name, phone, avatar)
                                }
                            },
                            onBack = presentationGate.guard { view = SettingsView.Menu },
                        )

                        SettingsView.ChangePassword -> ChangePasswordScreen(
                            onChangePassword = { old, new ->
                                nav.runAdmittedUiAction(presentationGate, onClosed = { false }) {
                                    nav.account.changePassword(old, new)
                                }
                            },
                            onBack = presentationGate.guard { view = SettingsView.Menu },
                        )

                        SettingsView.Devices -> DeviceManagementScreen(
                            devices = nav.account.devices.map {
                                DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin)
                            },
                            currentDeviceId = nav.account.currentDeviceId,
                            onKick = presentationGate.guard(nav.account::kickDevice),
                            onBack = presentationGate.guard { view = SettingsView.Menu },
                        )

                        SettingsView.Blacklist -> BlacklistScreen(
                            blockedUsers = nav.account.blockedContacts.map {
                                BlockedUser(it.friendUid, it.user?.name ?: it.friendUid)
                            },
                            onUnblock = presentationGate.guard(nav.account::unblockContact),
                            onBack = presentationGate.guard { view = SettingsView.Menu },
                        )
                    }
                }
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = presentationGate.guard { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？退出后需要重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = presentationGate.guard {
                        showLogoutConfirm = false
                        onDismiss()
                        onLogout()
                    },
                    modifier = Modifier.testTag("settings.logout.confirm"),
                ) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = presentationGate.guard { showLogoutConfirm = false },
                    modifier = Modifier.testTag("settings.logout.cancel"),
                ) { Text("取消") }
            },
        )
    }
}

/** 菜单视图按内容收敛，子视图使用固定高度保证共享屏幕有稳定的滚动边界。 */
private fun SettingsView.dialogHeightModifier() = when (this) {
    SettingsView.Menu -> Modifier.heightIn(max = 686.dp)
    SettingsView.EditProfile -> Modifier.height(520.dp)
    SettingsView.ChangePassword -> Modifier.height(420.dp)
    SettingsView.Devices -> Modifier.height(540.dp)
    SettingsView.Blacklist -> Modifier.height(540.dp)
}

@Composable
private fun SettingsMenuView(
    nav: DesktopNav,
    buildInfoText: String,
    onOpenView: (SettingsView) -> Unit,
    onLogoutRequest: () -> Unit,
    onClose: () -> Unit,
) {
    val currentUser = nav.account.currentUser
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            SettingsIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "关闭",
                onClick = onClose,
                tag = "settings.close",
            )
        }

        SettingsProfileHeader(
            name = currentUser?.name?.ifBlank { null } ?: currentUser?.username ?: "未知",
            username = currentUser?.username,
            uid = currentUser?.uid,
            avatarName = currentUser?.name?.ifBlank { null } ?: currentUser?.username,
            avatar = currentUser?.avatar,
            onEditProfile = { onOpenView(SettingsView.EditProfile) },
        )

        Spacer(Modifier.height(12.dp))
        SettingsSectionLabel("账号")
        SettingsGroupCard {
            SettingsEntryRow(
                icon = Icons.Filled.Person,
                title = "编辑资料",
                description = "设置头像、显示名与手机号",
                onClick = { onOpenView(SettingsView.EditProfile) },
                tag = "settings.编辑资料",
            )
            SettingsEntryRow(
                icon = Icons.Filled.Lock,
                title = "修改密码",
                description = "定期更换密码，保护账号安全",
                onClick = { onOpenView(SettingsView.ChangePassword) },
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
                onClick = { onOpenView(SettingsView.Devices) },
                tag = "settings.设备管理",
            )
            SettingsEntryRow(
                icon = Icons.Filled.Block,
                title = "黑名单",
                description = "管理已屏蔽的联系人",
                onClick = { onOpenView(SettingsView.Blacklist) },
                tag = "settings.黑名单",
            )
        }

        Spacer(Modifier.height(10.dp))
        SettingsSectionLabel("通用")
        SettingsGroupCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Spacer(Modifier.weight(1f))
                ThemeSegmentedSelector(modifier = Modifier.width(248.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsDangerAction(
            text = "退出登录",
            icon = Icons.Filled.Logout,
            onClick = onLogoutRequest,
            tag = "settings.logout",
        )

        if (buildInfoText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = buildInfoText,
                style = MaterialTheme.typography.labelSmall,
                color = Tk.colors.metaText,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 大头像 + 身份信息；头像点击进入编辑资料（头像更换入口）。 */
@Composable
private fun SettingsProfileHeader(
    name: String,
    username: String?,
    uid: String?,
    avatarName: String?,
    avatar: com.virjar.tk.protocol.model.Attachment?,
    onEditProfile: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val avatarInteraction = remember { MutableInteractionSource() }
        val hovered by avatarInteraction.collectIsHoveredAsState()
        Box(modifier = Modifier.hoverable(avatarInteraction)) {
            Box(
                modifier = Modifier
                    .testTag("settings.avatar.edit")
                    .clip(Tk.avatarShape(72.dp))
                    .clickable(
                        interactionSource = avatarInteraction,
                        indication = null,
                        onClick = onEditProfile,
                    ),
            ) {
                AvatarPlaceholder(name = avatarName, avatar = avatar, size = 72)
                if (hovered) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "更换头像",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        val metaText = buildString {
            if (!username.isNullOrBlank()) append("@$username")
            if (!uid.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("UID ${uid.take(12)}")
            }
        }
        if (metaText.isNotEmpty()) {
            Text(metaText, style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
        }
    }
}
