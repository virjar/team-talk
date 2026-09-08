package com.virjar.tk.android

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.protocol.model.Attachment

/** 搜索摘要经当前领域读取确认后，仍由标准账号媒体 owner 下载和打开。 */
@Composable
internal fun rememberContentSearchDownloads(
    data: AppDataState,
    owner: AndroidAuthenticatedResourceOwner,
    onTextPreview: (Attachment) -> Unit,
): AndroidFileDownloadController? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportPermission = rememberAndroidMediaExportPermission()
    val currentTextPreview = rememberUpdatedState(onTextPreview)
    val lease = remember(data, owner, context, scope, exportPermission) {
        owner.acquire {
            AndroidAuthenticatedMediaResources.create(
                createMediaSession = {
                    AndroidMediaSession.create(
                        deploymentIdentity = data.deploymentIdentity,
                        datasetId = data.datasetId,
                        ownerUid = data.userSession.uid,
                        credentialsProvider = data::httpCredentialsSnapshot,
                        onAuthExpired = data::reportHttpAuthExpired,
                    )
                },
                createFileDownloads = { mediaSession ->
                    AndroidFileDownloadController(context, mediaSession, scope,
                        onTextAttachmentPreview = { currentTextPreview.value(it) },
                        telemetry = data.telemetry,
                        telemetryPage = ClientUiPage.SEARCH_MESSAGES,
                        requestExportPermission = exportPermission)
                },
            )
        }
    }
    DisposableEffect(lease) {
        val faults = AndroidPlatformLifecycleFaultReporter(data.telemetry, ClientUiPage.SEARCH_MESSAGES)
        onDispose {
            disposeAndroidAuthenticatedResources(lease::close) { failure ->
                faults.report()
                Log.e("ContentSearch", "Failed to dispose authenticated media", failure)
            }
        }
    }
    return lease.resourceOrNull()?.fileDownloads
}
