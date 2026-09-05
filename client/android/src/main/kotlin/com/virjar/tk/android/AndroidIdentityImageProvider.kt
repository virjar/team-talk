package com.virjar.tk.android

import android.graphics.Color
import android.util.Log
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ProtocolCompatibility
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.bridge.IdentityImageMediaConfig
import com.virjar.tk.app.ui.bridge.LocalIdentityImageMediaConfig

/** 已认证的 Android 外壳入口；头像 I/O 在同一会话边界内被持有。 */
@Composable
internal fun AndroidMainApp(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    connectionState: ConnectionState,
    protocolCompatibility: ProtocolCompatibility?,
    notificationNavigation: AndroidNotificationNavigation,
    onLogout: () -> Unit,
) {
    if (!dataState.acceptsRendering) return
    AndroidIdentityImageProvider(dataState, resourceOwner) {
        AndroidMainAppContent(
            dataState, resourceOwner, connectionState, protocolCompatibility, notificationNavigation, onLogout,
        )
    }
}

/** 为整个 Android 外壳安装一个已认证的、本地优先的头像媒体会话。 */
@Composable
internal fun AndroidIdentityImageProvider(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    content: @Composable () -> Unit,
) {
    val lease = remember(dataState, resourceOwner) {
        resourceOwner.acquire {
            AndroidMediaSession.create(
                deploymentIdentity = dataState.deploymentIdentity,
                datasetId = dataState.datasetId,
                ownerUid = dataState.userSession.uid,
                credentialsProvider = dataState::httpCredentialsSnapshot,
                onAuthExpired = dataState::reportHttpAuthExpired,
            )
        }
    }
    DisposableEffect(lease, dataState.telemetry) {
        val lifecycleFault = AndroidPlatformLifecycleFaultReporter(
            telemetry = dataState.telemetry,
            page = ClientUiPage.CONVERSATIONS,
        )
        onDispose {
            disposeAndroidAuthenticatedResources(
                closeResources = lease::close,
                recordFailure = { failure ->
                    lifecycleFault.report()
                    Log.e("Avatar", "Failed to dispose authenticated avatar media", failure)
                },
            )
        }
    }
    val mediaSession = lease.resourceOrNull()
    val media = remember(mediaSession) {
        mediaSession?.let { session ->
            IdentityImageMediaConfig { attachment, modifier ->
                rememberAsyncThumb(
                    attachment = attachment,
                    mediaSession = session,
                    modifier = modifier,
                    placeholderColor = Color.TRANSPARENT,
                    scaleType = ImageView.ScaleType.CENTER_CROP,
                )
            }
        }
    }
    CompositionLocalProvider(LocalIdentityImageMediaConfig provides media, content = content)
}
