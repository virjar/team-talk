package com.virjar.tk.android

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.ScreenDataKey
import com.virjar.tk.app.ui.component.textAttachmentPreviewKind
import com.virjar.tk.app.ui.screen.GroupFilesScreen
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.MediaOperationAttemptTracker
import com.virjar.tk.app.telemetry.MediaOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun NavGraphBuilder.androidTextAttachmentPreviewRoute(
    navController: NavHostController,
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
) {
    composable(
        Routes.TEXT_ATTACHMENT_PREVIEW,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType },
            navArgument("contentType") { type = NavType.StringType },
            navArgument("size") { type = NavType.LongType },
        ),
    ) { entry ->
        if (!dataState.acceptsRendering) return@composable
        val actionAdmission = dataState.uiActionAdmission
        val attachment = remember(entry) {
            try {
                Attachment(
                    path = decodeAttachmentRouteValue(
                        entry.arguments?.getString("path").orEmpty(),
                    ),
                    name = decodeAttachmentRouteValue(
                        entry.arguments?.getString("name").orEmpty(),
                    ),
                    contentType = decodeAttachmentRouteValue(
                        entry.arguments?.getString("contentType").orEmpty(),
                    ),
                    size = entry.arguments?.getLong("size") ?: 0L,
                )
            } catch (_: Exception) {
                null
            }
        }
        val previewKind = attachment?.let { textAttachmentPreviewKind(it) }
        if (attachment == null || previewKind == null) {
            LaunchedEffect(entry) {
                actionAdmission.runIfOpen { navController.popBackStack() }
            }
        } else {
            val sessionUser = dataState.userSession
            val ownerUid = sessionUser.uid
            val telemetry = dataState.telemetry
            val mediaResourcesLease = remember(
                ownerUid,
                dataState.datasetId,
                dataState,
                resourceOwner,
                telemetry,
            ) {
                resourceOwner.acquire {
                    AndroidAuthenticatedMediaResources.create(
                        createMediaSession = {
                            AndroidMediaSession.create(
                                deploymentIdentity = dataState.deploymentIdentity,
                                datasetId = dataState.datasetId,
                                ownerUid = ownerUid,
                                credentialsProvider = dataState::httpCredentialsSnapshot,
                                onAuthExpired = dataState::reportHttpAuthExpired,
                            )
                        },
                    )
                }
            }
            DisposableEffect(mediaResourcesLease, telemetry) {
                val lifecycleFault = AndroidPlatformLifecycleFaultReporter(
                    telemetry = telemetry,
                    page = ClientUiPage.TEXT_ATTACHMENT_PREVIEW,
                )
                onDispose {
                    disposeAndroidAuthenticatedResources(
                        closeResources = mediaResourcesLease::close,
                        recordFailure = { failure ->
                            lifecycleFault.report()
                            Log.e(
                                "TextAttachment",
                                "Failed to dispose authenticated media",
                                failure,
                            )
                        },
                    )
                }
            }
            mediaResourcesLease.resourceOrNull()?.let { resources ->
                AndroidTextAttachmentPreviewScreen(
                    attachment = attachment,
                    mediaSession = resources.mediaSession,
                    onBack = actionAdmission.guard { navController.popBackStack() },
                )
            }
        }
    }
}

internal fun NavGraphBuilder.androidGroupFilesRoute(
    navController: NavHostController,
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
) {
    composable(
        Routes.GROUP_FILES,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
    ) { entry ->
        if (!dataState.acceptsRendering) return@composable
        val actionAdmission = dataState.uiActionAdmission
        val chatId = entry.arguments?.getString("chatId") ?: return@composable
        val context = LocalContext.current
        val sessionUser = dataState.userSession
        val sessionUid = sessionUser.uid
        val telemetry = dataState.telemetry
        val fileDownloadUiScope = rememberCoroutineScope()
        val mediaResourcesLease = remember(
            dataState.deploymentIdentity,
            dataState.datasetId,
            sessionUid,
            dataState,
            resourceOwner,
            context,
            fileDownloadUiScope,
            telemetry,
        ) {
            resourceOwner.acquire {
                AndroidAuthenticatedMediaResources.create(
                    createMediaSession = {
                        AndroidMediaSession.create(
                            deploymentIdentity = dataState.deploymentIdentity,
                            datasetId = dataState.datasetId,
                            ownerUid = sessionUid,
                            credentialsProvider = dataState::httpCredentialsSnapshot,
                            onAuthExpired = dataState::reportHttpAuthExpired,
                        )
                    },
                    createFileDownloads = { mediaSession ->
                        AndroidFileDownloadController(
                            context = context,
                            mediaSession = mediaSession,
                            uiScope = fileDownloadUiScope,
                            telemetry = telemetry,
                            telemetryPage = ClientUiPage.GROUP_FILES,
                        )
                    },
                )
            }
        }
        DisposableEffect(mediaResourcesLease, telemetry) {
            val lifecycleFault = AndroidPlatformLifecycleFaultReporter(
                telemetry = telemetry,
                page = ClientUiPage.GROUP_FILES,
            )
            onDispose {
                disposeAndroidAuthenticatedResources(
                    closeResources = mediaResourcesLease::close,
                    recordFailure = { failure ->
                        lifecycleFault.report()
                        Log.e("GroupFiles", "Failed to dispose authenticated media", failure)
                    },
                )
            }
        }
        val mediaResources = mediaResourcesLease.resourceOrNull() ?: return@composable
        val mediaSession = mediaResources.mediaSession
        val downloads = requireNotNull(mediaResources.fileDownloads)
        var uploading by remember { mutableStateOf(false) }
        var versionTarget by remember { mutableStateOf<GroupFileEntry?>(null) }
        LaunchedEffect(chatId) {
            dataState.runAdmittedUiAction(actionAdmission, onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.GroupFiles(chatId))
            }
        }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                dataState.launchAdmittedUiAction(actionAdmission) {
                    uploading = true
                    var selected: PreparedMedia? = null
                    val uploadAttempt = MediaOperationAttemptTracker { outcome, reason ->
                        telemetry.recordMedia(
                            ClientUiPage.GROUP_FILES,
                            ClientMediaKind.FILE,
                            MediaOperation.UPLOAD,
                            outcome,
                            reason,
                        )
                    }
                    try {
                        uploadAttempt.start()
                        selected = MediaHelper.prepareSelectedMedia(context, uri, mediaSession)
                        val name = selected.fileName
                        val attachment = MediaHelper.uploadFile(
                            selected.file,
                            name,
                            selected.contentType,
                            mediaSession,
                        )
                        currentCoroutineContext().ensureActive()
                        // 文件选择器返回时路由可能已切到另一群；此时取消发布，绝不能借用 B 的当前目录。
                        if (dataState.groupFiles.chatId != chatId) {
                            uploadAttempt.cancel()
                            return@launchAdmittedUiAction
                        }
                        val target = versionTarget
                        if (target == null) {
                            dataState.groupFiles.publish(name, attachment)
                        } else {
                            dataState.groupFiles.addVersion(target, attachment)
                        }
                        // UPLOAD 描述已完成的 FileStore 传输。该功能会单独记录 PUBLISH_GROUP_FILE，
                        // 因此上传可能准确成功，而被拒绝的目录发布则会记录其自身的 FAILED 终态。
                        uploadAttempt.succeed()
                    } catch (cancelled: CancellationException) {
                        uploadAttempt.cancel()
                        throw cancelled
                    } catch (failure: Exception) {
                        val reason = classifyAndroidMediaFailure(failure)
                        Log.w("GroupFiles", "群文件上传失败: ${reason.code}")
                        uploadAttempt.fail(reason)
                        actionAdmission.runIfOpen {
                            dataState.groupFiles.reportUploadError(failure)
                        }
                    } finally {
                        selected?.delete()
                        actionAdmission.runIfOpen {
                            versionTarget = null
                            uploading = false
                        }
                    }
                }
            } else {
                actionAdmission.runIfOpen { versionTarget = null }
            }
        }

        val filesReady = dataState.groupFiles.chatId == chatId
        GroupFilesScreen(
            entries = dataState.groupFiles.entries.takeIf { filesReady }.orEmpty(),
            path = dataState.groupFiles.path.takeIf { filesReady }.orEmpty(),
            selectedFile = dataState.groupFiles.selectedFile.takeIf { filesReady },
            versions = dataState.groupFiles.versions.takeIf { filesReady }.orEmpty(),
            loading = !filesReady || dataState.groupFiles.loading,
            uploading = uploading,
            stale = filesReady && dataState.groupFiles.stale,
            onRefresh = {
                dataState.launchAdmittedUiAction { dataState.groupFiles.refresh() }
            },
            onEnter = actionAdmission.guard(dataState.groupFiles::enter),
            onUp = actionAdmission.guard(dataState.groupFiles::up),
            onCreateFolder = actionAdmission.guard(dataState.groupFiles::createFolder),
            onUpload = actionAdmission.guard {
                versionTarget = null
                picker.launch(arrayOf("*/*"))
            },
            onOpenFile = actionAdmission.guard(downloads::openOrDownload),
            onShowVersions = actionAdmission.guard(dataState.groupFiles::showVersions),
            onUploadVersion = actionAdmission.guard { target: GroupFileEntry ->
                versionTarget = target
                picker.launch(arrayOf("*/*"))
            },
            onRename = actionAdmission.guard(dataState.groupFiles::rename),
            onDelete = actionAdmission.guard(dataState.groupFiles::delete),
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
}
