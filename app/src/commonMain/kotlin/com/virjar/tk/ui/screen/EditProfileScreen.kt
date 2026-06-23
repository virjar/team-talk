package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.User
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun EditProfileScreen(
    currentUser: User?,
    onSave: suspend (name: String, phone: String?) -> Boolean,
    onBack: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(currentUser?.name ?: "") }
    var phone by remember { mutableStateOf(currentUser?.phone ?: "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "编辑资料",
            onBack = onBack?.let { { it() } },  // 取消=onBack
            trailing = {
                TextButton(
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            val success = onSave(name, phone.ifBlank { null })
                            saving = false
                            if (success) onBack?.invoke() else error = "保存失败"
                        }
                    },
                    modifier = Modifier.testTag("profile.save"),
                    enabled = name.isNotBlank() && !saving,
                ) { if (saving) Text("保存中…") else Text("保存") }
            },
        )

        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("显示名") },
                modifier = Modifier.fillMaxWidth().testTag("profile.name"),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("手机号") },
                modifier = Modifier.fillMaxWidth().testTag("profile.phone"),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
