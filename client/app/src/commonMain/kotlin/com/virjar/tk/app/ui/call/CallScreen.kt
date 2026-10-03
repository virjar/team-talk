package com.virjar.tk.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WifiCalling3
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.virjar.tk.shared.call.CallDirection
import com.virjar.tk.shared.call.CallPhase
import com.virjar.tk.shared.call.CallViewState
import com.virjar.tk.shared.client.LocalCache

/**
 * 全屏 1:1 通话界面（协议 minor 0.5）：来电接听/拒接、去电等待、媒体协商与通话中控制。
 * 由各端壳层在 [com.virjar.tk.shared.call.CallCenter.state] 非空时覆盖呈现。
 */
@Composable
fun CallScreen(
    state: CallViewState,
    localCache: LocalCache,
    callCenter: com.virjar.tk.shared.call.CallCenter,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val user by localCache.observeUser(state.peerUid).collectAsStateWithLifecycle(initialValue = null)
    val peerName = user?.name?.takeIf { it.isNotBlank() } ?: state.peerUid
    val remoteHandle by callCenter.remoteVideo.collectAsStateWithLifecycle()
    val localHandle by callCenter.localVideo.collectAsStateWithLifecycle()
    val cameraNotice by callCenter.localCameraNotice.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF141414))) {
        // 远端画面（视频通话接通后铺满；语音/未接通为纯色背景）
        if (state.video) {
            CallVideoSurface(remoteHandle, mirror = false, modifier = Modifier.fillMaxSize())
        }

        // 本地预览小窗
        if (state.video && (state.phase == CallPhase.ACTIVE || state.phase == CallPhase.CONNECTING)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 96.dp, height = 144.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                CallVideoSurface(localHandle, mirror = true, modifier = Modifier.fillMaxSize())
                if (cameraNotice != null) {
                    Text(
                        "摄像头不可用",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                    )
                }
            }
        }

        // 采集失败原因横幅：本地预览小窗放不下完整句子，通话不被中断但原因必须可见
        if (state.video && cameraNotice != null) {
            Text(
                cameraNotice!!,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        when (state.phase) {
            CallPhase.RINGING -> RingingStage(state, peerName, callCenter, Modifier.align(Alignment.Center))
            CallPhase.CONNECTING -> CenterStatus("连接中…", peerName, state)
            CallPhase.ACTIVE -> Unit
            CallPhase.ENDED -> EndedStage(state, peerName, callCenter)
        }

        if (state.phase == CallPhase.ACTIVE) {
            ActiveControls(state, callCenter, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun RingingStage(
    state: CallViewState,
    peerName: String,
    callCenter: com.virjar.tk.shared.call.CallCenter,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = if (state.video) Icons.Filled.PhotoCamera else Icons.Outlined.Call,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(peerName, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                state.direction == CallDirection.INCOMING -> if (state.video) "邀请你进行视频通话" else "邀请你进行语音通话"
                else -> "正在等待对方接受邀请…"
            },
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(48.dp))
        if (state.direction == CallDirection.INCOMING) {
            Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                CallActionCircle(
                    icon = Icons.Filled.CallEnd,
                    contentDescription = "拒接",
                    container = Color(0xFFE5484D),
                ) { scope.launch { callCenter.declineIncoming() } }
                CallActionCircle(
                    icon = Icons.Filled.WifiCalling3,
                    contentDescription = "接听",
                    container = Color(0xFF2E9E5B),
                ) { scope.launch { callCenter.acceptIncoming() } }
            }
        } else {
            CallActionCircle(
                icon = Icons.Filled.CallEnd,
                contentDescription = "取消",
                container = Color(0xFFE5484D),
            ) { scope.launch { callCenter.hangUp() } }
        }
    }
}

@Composable
private fun EndedStage(
    state: CallViewState,
    peerName: String,
    callCenter: com.virjar.tk.shared.call.CallCenter,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(peerName, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.endReason) {
                com.virjar.tk.protocol.model.CallEndReason.DECLINED -> "对方已拒绝"
                com.virjar.tk.protocol.model.CallEndReason.CANCELLED -> "已取消"
                com.virjar.tk.protocol.model.CallEndReason.BUSY -> "对方忙线"
                com.virjar.tk.protocol.model.CallEndReason.TIMEOUT -> "对方无应答"
                com.virjar.tk.protocol.model.CallEndReason.CONNECTION_LOST -> "连接已中断"
                else -> "通话已结束"
            },
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(40.dp))
        CallActionCircle(
            icon = Icons.Filled.CallEnd,
            contentDescription = "关闭",
            container = Color(0xFFE5484D),
        ) { callCenter.dismissEnded() }
    }
}

@Composable
private fun CenterStatus(status: String, peerName: String, state: CallViewState) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(peerName, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(status, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActiveControls(
    state: CallViewState,
    callCenter: com.virjar.tk.shared.call.CallCenter,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val cameraSwitchable by callCenter.cameraSwitchable.collectAsStateWithLifecycle()
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
    ) {
        CallActionCircle(
            icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            contentDescription = if (state.muted) "取消静音" else "静音",
            container = if (state.muted) Color(0xFF5A5A5A) else Color(0xFF2E2E2E),
        ) { callCenter.setMuted(!state.muted) }
        // 前后摄切换只在确有多摄像头时才有意义（单摄桌面隐藏）
        if (state.video && cameraSwitchable) {
            CallActionCircle(
                icon = Icons.Filled.Cameraswitch,
                contentDescription = "切换摄像头",
                container = Color(0xFF2E2E2E),
            ) { callCenter.switchCamera() }
        }
        CallActionCircle(
            icon = if (state.speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
            contentDescription = if (state.speakerOn) "切换听筒" else "切换扬声器",
            container = Color(0xFF2E2E2E),
        ) { callCenter.setSpeakerphone(!state.speakerOn) }
        CallActionCircle(
            icon = Icons.Filled.CallEnd,
            contentDescription = "挂断",
            container = Color(0xFFE5484D),
        ) { scope.launch { callCenter.hangUp() } }
    }
}

@Composable
private fun CallActionCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    container: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(68.dp).clip(CircleShape).background(container)) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}
