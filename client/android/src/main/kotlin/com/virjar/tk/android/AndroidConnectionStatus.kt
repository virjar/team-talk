package com.virjar.tk.android

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.client.ConnectionState

/**
 * 已认证外壳的连接真值。每个非权威状态都会保持本地会话挂载，
 * 因此其文案绝不能暗示服务器数据已经是最新的。
 */
internal fun androidConnectionStatusText(state: ConnectionState): String? = when (state) {
    ConnectionState.AUTHENTICATED -> null
    ConnectionState.DISCONNECTED -> "离线：当前使用本地内容"
    ConnectionState.CONNECTING -> "尚未连接，正在重试：当前使用本地内容"
    ConnectionState.CONNECTED -> "已连接，正在认证：当前使用本地内容"
    ConnectionState.SYNCHRONIZING -> "正在同步：当前使用本地内容"
    // 权威性的吊销会退役本地会话并离开本外壳。因此可见的 AUTH_FAILED 是可重试的，
    // 并且必须继续描述仍然挂载的本地图数据。
    ConnectionState.AUTH_FAILED -> "认证失败，等待重试：当前使用本地内容"
}

@Composable
internal fun AndroidConnectionStatusBanner(state: ConnectionState) {
    val text = androidConnectionStatusText(state) ?: return
    val isFailure = state == ConnectionState.DISCONNECTED || state == ConnectionState.AUTH_FAILED
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("status.connection"),
        color = if (isFailure) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        contentColor = if (isFailure) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}
