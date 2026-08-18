package com.virjar.tk.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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

/**
 * @param windowStyle 桌面登录窗口样式（doc/04-ui-design §2.3）：窗口即卡片——
 * 无渐变背景、无内嵌卡片，内容宽 360、按钮高 40；false = 移动端全屏（渐变+卡片）。
 */
@Composable
fun LoginScreen(
    onLogin: (username: String, password: String) -> Unit,
    onNavigateToRegister: () -> Unit,
    error: String? = null,
    loading: Boolean = false,
    windowStyle: Boolean = false,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
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
            .then(
                if (windowStyle) Modifier.background(MaterialTheme.colorScheme.background)
                else Modifier.background(
                    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.background))
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (windowStyle) 30.dp else 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuthHeader(
                title = "TeamTalk",
                titleColor = if (windowStyle) MaterialTheme.colorScheme.onBackground else Color.White,
            )
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
}
