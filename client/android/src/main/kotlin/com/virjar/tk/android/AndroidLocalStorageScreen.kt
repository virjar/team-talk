package com.virjar.tk.android

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.client.LocalCacheStorageCompactionReport
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.theme.Tk

/** 认证外的 Android 维护页；任务由 Application 持有，Activity 只观察状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidLocalStorageScreen(
    preparing: Boolean,
    running: Boolean,
    canCompact: Boolean,
    canReturn: Boolean,
    requiresRestart: Boolean,
    result: LocalCacheStorageCompactionReport?,
    errorMessage: String?,
    onCompact: () -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler {
        if (canReturn) onBack()
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("本地存储") },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    enabled = canReturn,
                    modifier = Modifier.testTag("storage.back"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsGroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("整理本地数据库", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "回收当前账号数据库中的空闲空间，保留聊天记录、草稿和待发送内容。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tk.colors.secondaryText,
                    )
                    Text(
                        "整理前会保存草稿并暂停会话。登录信息保留，完成后返回应用即可继续使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tk.colors.secondaryText,
                    )
                    Button(
                        onClick = onCompact,
                        enabled = canCompact,
                        modifier = Modifier.fillMaxWidth().testTag("storage.compact"),
                    ) {
                        Text(when {
                            preparing -> "正在准备…"
                            running -> "正在整理…"
                            errorMessage != null -> "重试整理"
                            else -> "整理数据库"
                        })
                    }
                }
            }
            if (preparing || running) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("storage.running"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (preparing) "正在保存草稿并暂停会话…" else "正在整理…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "可以切换到其他应用。请等待完成后返回 TeamTalk。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tk.colors.secondaryText,
                    )
                }
            }
            errorMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag("storage.error"),
                )
            }
            result?.let { report ->
                SettingsGroupCard(modifier = Modifier.testTag("storage.result")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("整理完成", style = MaterialTheme.typography.titleMedium)
                        StorageSizeRow("整理前", Formatter.formatFileSize(context, report.bytesBefore))
                        StorageSizeRow("整理后", Formatter.formatFileSize(context, report.bytesAfter))
                        StorageSizeRow("已回收", Formatter.formatFileSize(context, report.reclaimedBytes))
                        if (report.reclaimedBytes == 0L) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "本次没有回收更多空间。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Tk.colors.secondaryText,
                            )
                        }
                    }
                }
            }
            if (requiresRestart) {
                Button(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth().testTag("storage.exit"),
                ) {
                    Text("关闭应用")
                }
            } else {
                Button(
                    onClick = onBack,
                    enabled = canReturn,
                    modifier = Modifier.fillMaxWidth().testTag("storage.return"),
                ) {
                    Text("返回应用")
                }
            }
        }
    }
}

@Composable
private fun StorageSizeRow(label: String, size: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tk.colors.secondaryText)
        Text(size, style = MaterialTheme.typography.bodyMedium)
    }
}
