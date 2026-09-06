package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.Outcome
import kotlinx.coroutines.launch

/** 两端资料页共用同一个编辑动作；打开时取本地权威备注，未保存的草稿不被同步事件覆盖。 */
@Composable
internal fun FriendRemarkEditor(remark: String?, onSave: suspend (String?) -> Outcome<Unit>) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    OutlinedButton(
        onClick = { draft = remark.orEmpty(); error = null; editing = true },
        modifier = Modifier.fillMaxWidth().testTag("profile.remark.edit"),
    ) { Text(remark?.takeIf(String::isNotBlank)?.let { "备注：$it" } ?: "设置好友备注") }

    if (editing) {
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text("好友备注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            if (it.length <= 100) { draft = it; error = null }
                            else error = "好友备注不能超过 100 个字符"
                        },
                        enabled = !saving,
                        singleLine = true,
                        label = { Text("备注名") },
                        supportingText = { Text("仅自己可见；留空恢复对方的显示名") },
                        modifier = Modifier.fillMaxWidth().testTag("profile.remark.input"),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("profile.remark.error"))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    modifier = Modifier.testTag("profile.remark.save"),
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            try {
                                when (val result = onSave(draft.trim().ifBlank { null })) {
                                    is Outcome.Success -> editing = false
                                    is Outcome.Failure -> error = profileSaveFailureMessage(result.error)
                                }
                            } finally { saving = false }
                        }
                    },
                ) { Text(if (saving) "保存中…" else "保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = { editing = false }, enabled = !saving,
                    modifier = Modifier.testTag("profile.remark.cancel"),
                ) { Text("取消") }
            },
        )
    }
}
