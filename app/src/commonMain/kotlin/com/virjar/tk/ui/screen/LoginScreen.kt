package com.virjar.tk.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.client.AuthRules
import com.virjar.tk.ui.component.AuthCard
import com.virjar.tk.ui.component.AuthError
import com.virjar.tk.ui.component.AuthField
import com.virjar.tk.ui.component.AuthHeader
import com.virjar.tk.ui.component.AuthSubmitButton
import com.virjar.tk.ui.component.AuthSwitchLink

@Composable
fun LoginScreen(
    onLogin: (username: String, password: String) -> Unit,
    onNavigateToRegister: () -> Unit,
    error: String? = null,
    loading: Boolean = false,
    allowCustomServer: Boolean = false,
    serverUrl: String = "",
    onServerUrlChange: ((String) -> Unit)? = null,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    val displayError = localError ?: error

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.background)))
    ) {
        // 右上角服务器设置入口
        if (allowCustomServer) {
            IconButton(
                onClick = { showServerDialog = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "服务器设置", tint = Color.White)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuthHeader(title = "TeamTalk")
            if (allowCustomServer && serverUrl.isNotEmpty()) {
                Text(serverUrl, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(36.dp))

            AuthCard {
                AuthField("用户名", username, { username = it; localError = null }, "login.username")
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
                )
            }
            Spacer(Modifier.height(16.dp))
            AuthSwitchLink("没有账号？注册", onNavigateToRegister, "login.gotoRegister")
        }
    }

    // 服务器地址编辑对话框
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
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://your-server.com") },
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
                TextButton(onClick = { showServerDialog = false }) { Text("取消") }
            },
        )
    }
}
