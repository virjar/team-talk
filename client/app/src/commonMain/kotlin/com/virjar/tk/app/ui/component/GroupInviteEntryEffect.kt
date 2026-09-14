package com.virjar.tk.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import com.virjar.tk.app.navigation.feature.DiscoveryFeature
import com.virjar.tk.app.ui.platform.readPlainText
import kotlinx.coroutines.CancellationException

/** 仅在已认证窗口获得焦点时读取一次文本；未变化的邀请不会反复打断当前会话。 */
@Composable
fun GroupInviteEntryEffect(
    discovery: DiscoveryFeature,
    active: Boolean,
    onInvite: (String) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val openInvite by rememberUpdatedState(onInvite)
    var lastInvite by rememberSaveable(discovery) { mutableStateOf<String?>(null) }
    LaunchedEffect(discovery, clipboard, active, focused) {
        if (!active || !focused) return@LaunchedEffect
        val text = try {
            clipboard.readPlainText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 剪贴板暂被其他应用占用或系统拒绝读取时，不打断当前工作。
            null
        } ?: return@LaunchedEffect
        val invite = discovery.inviteFromText(text) ?: return@LaunchedEffect
        if (invite != lastInvite) {
            lastInvite = invite
            openInvite(invite)
        }
    }
}
