package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.theme.Tk

@Composable
fun DeviceManagementScreen(
    devices: List<DeviceInfo>,
    currentDeviceId: String? = null,
    onKick: (deviceId: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "设备管理", onBack = onBack)

        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无其他设备", style = MaterialTheme.typography.bodyLarge, color = Tk.colors.secondaryText)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(devices, key = { it.deviceId }) { device ->
                    SettingsGroupCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (device.deviceId == currentDeviceId) {
                                        "${device.deviceName}（当前设备）"
                                    } else {
                                        device.deviceName
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    "${device.deviceModel} · ${formatTime(device.lastLogin)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Tk.colors.secondaryText,
                                )
                            }
                            if (device.deviceId != currentDeviceId) {
                                OutlinedButton(
                                    onClick = { onKick(device.deviceId) },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                ) { Text("下线") }
                            }
                        }
                    }
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
