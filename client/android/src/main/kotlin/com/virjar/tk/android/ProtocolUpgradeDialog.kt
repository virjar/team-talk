package com.virjar.tk.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties

internal data class ProtocolUpgradeDialogPolicy(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissOnBackPress: Boolean,
    val dismissOnClickOutside: Boolean,
)

internal val forceProtocolUpgradeDialogPolicy = ProtocolUpgradeDialogPolicy(
    title = "客户端需要更新",
    message = "当前版本与服务器不兼容。请更新到最新版本后再继续使用 TeamTalk。",
    confirmLabel = "退出应用",
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
)

@Composable
internal fun ProtocolUpgradeDialog(
    onExit: () -> Unit,
    policy: ProtocolUpgradeDialogPolicy = forceProtocolUpgradeDialogPolicy,
    message: String = policy.message,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(policy.title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onExit,
                modifier = Modifier.testTag("auth.upgrade.exit"),
            ) {
                Text(policy.confirmLabel)
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = policy.dismissOnBackPress,
            dismissOnClickOutside = policy.dismissOnClickOutside,
        ),
        modifier = Modifier.testTag("auth.upgrade.dialog"),
    )
}
