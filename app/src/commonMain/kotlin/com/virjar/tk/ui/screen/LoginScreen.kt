package com.virjar.tk.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.auth.AuthRules
import com.virjar.tk.ui.component.AuthCard
import com.virjar.tk.ui.component.AuthError
import com.virjar.tk.ui.component.AuthField
import com.virjar.tk.ui.component.AuthHeader
import com.virjar.tk.ui.component.AuthSubmitButton
import com.virjar.tk.ui.component.AuthSwitchLink

/**
 * @param windowStyle 桌面登录窗口样式（doc/05-clients/desktop.md）：窗口即卡片，
 * 内容宽 360、按钮高 40；双端共用 primary→background 渐变背景。
 * @param allowCustomServer 演示站体验入口：登录页右上角可编辑服务器地址。
 *   仅用于 demo 构建临时体验，生产私有化路径是构建期注入地址（DeploymentConfig）。
 * @param onResetServerUrl 恢复打包默认服务器（对话框内入口；null = 不显示）。
 */
@Composable
fun LoginScreen(
    onLogin: (username: String, password: String) -> Unit,
    onNavigateToRegister: () -> Unit,
    error: String? = null,
    loading: Boolean = false,
    windowStyle: Boolean = false,
    allowCustomServer: Boolean = false,
    serverUrl: String = "",
    onServerUrlChange: ((String) -> Unit)? = null,
    onResetServerUrl: (() -> Unit)? = null,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    val displayError = localError ?: error

    // 窗口式启动焦点：用户名输入框（§3 交互规范；移动端不抢焦点避免弹键盘）
    val usernameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (windowStyle) {
            kotlinx.coroutines.delay(100)  // 等窗口完成首帧布局
            runCatching { usernameFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.background))
            )
    ) {
        // 右上角服务器设置入口（演示站体验；渐变顶部用白图标）
        if (allowCustomServer && onServerUrlChange != null) {
            IconButton(
                onClick = { showServerDialog = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("login.serverSettings"),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "服务器设置", tint = Color.White)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (windowStyle) 30.dp else 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuthHeader(
                title = "TeamTalk",
                titleColor = if (windowStyle) MaterialTheme.colorScheme.onBackground else Color.White,
            )
            if (allowCustomServer && serverUrl.isNotEmpty()) {
                Text(serverUrl, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(if (windowStyle) 28.dp else 36.dp))

            val form: @Composable ColumnScope.() -> Unit = {
                AuthField("用户名", username, { username = it; localError = null }, "login.username",
                    focusRequester = if (windowStyle) usernameFocus else null)
                Spacer(Modifier.height(12.dp))
                AuthField("密码", password, { password = it; localError = null }, "login.password", isPassword = true)
                if (displayError != null) AuthError(displayError)
                Spacer(Modifier.height(20.dp))
                AuthSubmitButton(
                    text = "登录",
                    onClick = {
                        localError = AuthRules.validateUsername(username)
                            ?: AuthRules.validatePassword(password)
                        if (localError != null) return@AuthSubmitButton
                        onLogin(username, password)
                    },
                    enabled = username.isNotBlank() && password.isNotBlank() && !loading,
                    loading = loading,
                    testTag = "login.submit",
                    height = if (windowStyle) 40.dp else 48.dp,
                )
            }
            if (windowStyle) {
                Column(modifier = Modifier.width(360.dp), content = form)
            } else {
                AuthCard(content = form)
            }
            Spacer(Modifier.height(16.dp))
            AuthSwitchLink("没有账号？注册", onNavigateToRegister, "login.gotoRegister")
        }
    }

    // 服务器地址编辑对话框（演示站体验入口）
    if (showServerDialog) {
        var editUrl by remember { mutableStateOf(serverUrl) }
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("服务器设置") },
            text = {
                Column {
                    Text("HTTP 地址（含 https://）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        modifier = Modifier.fillMaxWidth().testTag("login.serverUrl"),
                        singleLine = true,
                        placeholder = { Text("https://your-server.com") },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "演示用途：临时连接其他 TeamTalk 服务器。TCP 端口沿用当前配置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onServerUrlChange?.invoke(editUrl.trim())
                    showServerDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (onResetServerUrl != null) {
                        TextButton(onClick = {
                            onResetServerUrl.invoke()
                            showServerDialog = false
                        }) { Text("恢复默认") }
                    }
                    TextButton(onClick = { showServerDialog = false }) { Text("取消") }
                }
            },
        )
    }
}
