package com.virjar.tk.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
fun RegisterScreen(
    onRegister: (username: String, password: String, displayName: String) -> Unit,
    onNavigateBack: () -> Unit,
    error: String? = null,
    loading: Boolean = false,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val displayError = localError ?: error

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.background))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuthHeader(title = "注册")
            Spacer(Modifier.height(36.dp))

            AuthCard {
                AuthField("用户名", username, { username = it; localError = null }, "register.username")
                Spacer(Modifier.height(12.dp))
                AuthField("显示名", displayName, { displayName = it; localError = null }, "register.displayName")
                Spacer(Modifier.height(12.dp))
                AuthField("密码", password, { password = it; localError = null }, "register.password", isPassword = true)
                if (displayError != null) AuthError(displayError)
                Spacer(Modifier.height(20.dp))
                AuthSubmitButton(
                    text = "注册",
                    onClick = {
                        localError = AuthRules.validateUsername(username) ?: AuthRules.validatePassword(password)
                        if (localError != null) return@AuthSubmitButton
                        onRegister(username, password, displayName)
                    },
                    enabled = username.isNotBlank() && password.isNotBlank() && displayName.isNotBlank() && !loading,
                    loading = loading,
                    testTag = "register.submit",
                )
            }
            Spacer(Modifier.height(16.dp))
            AuthSwitchLink("已有账号？登录", onNavigateBack, "register.gotoLogin")
        }
    }
}
