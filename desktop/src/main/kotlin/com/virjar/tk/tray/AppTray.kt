package com.virjar.tk.tray

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.ApplicationScope
import com.kdroid.composetray.tray.api.Tray

/**
 * System tray composable — must be called within `application {}` scope,
 * as a sibling of the main Window.
 */
@Composable
fun ApplicationScope.AppTray(
    isConnected: Boolean,
    unreadCount: Int,
    onRestore: () -> Unit,
    onExit: () -> Unit,
) {
    val statusIcon: ImageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Close
    val statusText = if (isConnected) "在线" else "离线"
    val tooltipText = if (unreadCount > 0) "TeamTalk - $unreadCount 条未读" else "TeamTalk"

    Tray(
        icon = statusIcon,
        tint = null,
        tooltip = tooltipText,
        primaryAction = {
            onRestore()
        },
    ) {
        Item("打开 TeamTalk", onClick = {
            onRestore()
        })

        Divider()

        // Disabled item: shows connection status
        Item(statusText, isEnabled = false, onClick = {})

        Divider()

        Item("退出", onClick = { onExit() })
    }
}
