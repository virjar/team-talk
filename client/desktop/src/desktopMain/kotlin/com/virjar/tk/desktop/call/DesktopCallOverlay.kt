package com.virjar.tk.desktop.call

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.virjar.tk.app.ui.call.CallScreen
import com.virjar.tk.desktop.DesktopNav

/**
 * 桌面通话覆盖层（协议 minor 0.5）：主窗口内容顶层的全幅覆盖，
 * 与画廊全屏覆盖层（DesktopGalleryOverlay）同款宿主方式。
 * 会话退役时 CallCenter 状态被会话清理清空，覆盖层自然消失。
 */
@Composable
fun DesktopCallOverlay(nav: DesktopNav) {
    val callState by nav.callCenter.state.collectAsState()
    val current = callState ?: return
    CallScreen(
        state = current,
        localCache = nav.localCache,
        callCenter = nav.callCenter,
        modifier = Modifier.fillMaxSize(),
    )
}
