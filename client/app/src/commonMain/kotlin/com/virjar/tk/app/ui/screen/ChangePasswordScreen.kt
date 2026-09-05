package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.component.SettingsPrimaryButton
import com.virjar.tk.app.ui.component.TkFormTextField
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

    fun submit() {
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
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "修改密码", onBack = onBack)

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroupCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TkFormTextField(
                        oldPassword,
                        { oldPassword = it; error = null },
                        label = "旧密码",
                        visualTransformation = PasswordVisualTransformation(),
                        tag = "password.old",
                    )
                    TkFormTextField(
                        newPassword,
                        { newPassword = it; error = null },
                        label = "新密码",
                        visualTransformation = PasswordVisualTransformation(),
                        tag = "password.new",
                    )
                    TkFormTextField(
                        confirmPassword,
                        { confirmPassword = it; error = null },
                        label = "确认新密码",
                        visualTransformation = PasswordVisualTransformation(),
                        tag = "password.confirm",
                    )
                }
            }

            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (success) {
                Text(
                    "密码修改成功",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SettingsPrimaryButton(
                text = if (saving) "提交中…" else "确认修改",
                onClick = ::submit,
                enabled = !saving,
                tag = "password.submit",
            )
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
