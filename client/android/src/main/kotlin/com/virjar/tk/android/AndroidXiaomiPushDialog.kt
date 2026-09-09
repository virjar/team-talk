package com.virjar.tk.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/** 单一厂商接入的用户授权与关闭入口，拒绝不影响正常聊天。 */
@Composable
internal fun AndroidXiaomiPushDialog(settings: AndroidXiaomiPushSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled by settings.enabled.collectAsState()
    val pendingUnregister by settings.pendingUnregister.collectAsState()
    val status by settings.status.collectAsState()
    AlertDialog(
        // AlertDialog owns a separate window; apply the same mapping as TestTagEnabler to its content root.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = {
            if (settings.needsConsent) settings.setEnabled(false)
            onDismiss()
        },
        title = { Text("消息通知") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (enabled || pendingUnregister) status else "开启小米推送后，应用关闭时也可接收消息通知。通知只提示有新消息，点击后连接服务器查看。")
                Text("小米推送服务会处理应用及设备标识、推送标识和通知投递信息，用于将通知送达这台手机。")
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1534")))
                }) { Text("小米推送隐私政策") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("系统通知设置") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { settings.setEnabled(!enabled); onDismiss() },
                modifier = Modifier.testTag("settings.notifications.toggle"),
            ) { Text(if (enabled) "关闭小米推送" else "同意并开启") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (settings.needsConsent) settings.setEnabled(false)
                onDismiss()
            }, modifier = Modifier.testTag("settings.notifications.dismiss")) { Text(if (enabled) "完成" else "暂不开启") }
        },
    )
}
