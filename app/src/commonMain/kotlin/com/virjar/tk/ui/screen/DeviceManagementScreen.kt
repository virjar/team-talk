package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.ScreenHeader

@Composable
fun DeviceManagementScreen(
    devices: List<DeviceInfo>,
    onKick: (deviceId: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "设备管理", onBack = onBack)

        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无其他设备", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn {
                items(devices, key = { it.deviceId }) { device ->
                    ListItem(
                        headlineContent = { Text(device.deviceName) },
                        supportingContent = {
                            Text("${device.deviceModel} · ${formatTime(device.lastLogin)}", style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            OutlinedButton(onClick = { onKick(device.deviceId) }) { Text("下线") }
                        },
                    )
                }
            }
        }
    }
}

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val lastLogin: Long,
)

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        else -> "${diff / 86400_000}天前"
    }
}
