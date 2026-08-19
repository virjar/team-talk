package com.virjar.tk

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.ui.AppTheme
import com.virjar.tk.ui.screen.DocumentWorkspaceHost

/** Desktop 文档专用窗口；与主窗口共享同一个 session-scoped 工作台状态。 */
@Composable
internal fun DocumentWorkspaceWindow(nav: DesktopNav, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = "TeamTalk 文档",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        TestServiceBridge.registerWindowWithId("documents", window)
        DisposableEffect(Unit) {
            onDispose { TestServiceBridge.unregisterWindow("documents") }
        }
        setTeamTalkIcon()
        AppTheme {
            Surface(Modifier.fillMaxSize()) {
                DocumentWorkspaceHost(workspace = nav.documents, detached = true)
            }
        }
    }
}
