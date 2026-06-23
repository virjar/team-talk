package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun ChangePasswordScreen(
    onChangePassword: suspend (old: String, new: String) -> Boolean,
    onBack: (() -> Unit)? = null,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "修改密码",
            onBack = onBack,
            trailing = {
                TextButton(
                    modifier = Modifier.testTag("password.submit"),
                    onClick = {
                        when {
                            oldPassword.isBlank() -> error = "请输入旧密码"
                            newPassword.length < 6 -> error = "新密码至少6位"
                            newPassword != confirmPassword -> error = "两次密码不一致"
                            else -> scope.launch {
                                saving = true
                                error = null
                                val ok = onChangePassword(oldPassword, newPassword)
                                saving = false
                                if (ok) {
                                    // 成功：显示提示，短暂停留后关闭窗口
                                    success = true
                                } else {
                                    error = "密码错误"
                                }
                            }
                        }
                    },
                    enabled = !saving,
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("确认")
                    }
                }
            },
        )

        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(oldPassword, { oldPassword = it; error = null }, label = { Text("旧密码") },
                modifier = Modifier.fillMaxWidth().testTag("password.old"), visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(newPassword, { newPassword = it; error = null }, label = { Text("新密码") },
                modifier = Modifier.fillMaxWidth().testTag("password.new"), visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(confirmPassword, { confirmPassword = it; error = null }, label = { Text("确认新密码") },
                modifier = Modifier.fillMaxWidth().testTag("password.confirm"), visualTransformation = PasswordVisualTransformation(), singleLine = true)

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (success) {
                Spacer(Modifier.height(12.dp))
                Text("密码修改成功", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // 成功后短暂展示提示，然后关闭窗口
    if (success) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(800)
            onBack?.invoke()
        }
    }
}
