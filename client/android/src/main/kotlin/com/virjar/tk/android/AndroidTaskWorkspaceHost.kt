package com.virjar.tk.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.feature.task.TaskFeature
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.screen.TaskMaterialsUi
import com.virjar.tk.app.ui.screen.TaskWorkspaceScreen
import com.virjar.tk.app.ui.screen.loadMaterialDocumentCandidates
import com.virjar.tk.app.ui.screen.openMaterialDocument
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.OfficeRefBody

/** 任务文件沿账号媒体 owner 下载；每次表单独占选择器和未完成上传。 */
@Composable
internal fun AndroidTaskWorkspaceHost(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
    onOpenDocument: (OfficeRefBody) -> Unit,
) {
    val feature = dataState.tasks
    val pendingRestore = remember(dataState) { mutableStateOf<String?>(null) }
    val formSaver = remember(feature, pendingRestore) {
        Saver<TaskFeature, String>(
            save = { pendingRestore.value ?: it.saveEditorSnapshot() },
            restore = { payload -> pendingRestore.value = payload; feature },
        )
    }
    // 注册现有 owner；SavedState 只保存调用时的表单，不产生第二份实时编辑状态。
    rememberSaveable(dataState, saver = formSaver) { feature }
    val restorePayload = pendingRestore.value
    LaunchedEffect(dataState, restorePayload) {
        if (restorePayload != null) {
            dataState.runAdmittedUiAction(dataState.uiActionAdmission, onClosed = {}) {
                feature.restoreEditorSnapshot(restorePayload)
            }
            pendingRestore.value = null
        }
    }
    // 先恢复 editorKey，再让文件选择结果交给原表单。
    if (restorePayload != null) return
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val requestExportPermission = rememberAndroidMediaExportPermission()
    val mediaLease = remember(dataState, resourceOwner, uiScope, requestExportPermission) {
        resourceOwner.acquire {
            AndroidAuthenticatedMediaResources.create(
                createMediaSession = {
                    AndroidMediaSession.create(
                        deploymentIdentity = dataState.deploymentIdentity,
                        datasetId = dataState.datasetId,
                        ownerUid = dataState.userSession.uid,
                        credentialsProvider = dataState::httpCredentialsSnapshot,
                        onAuthExpired = dataState::reportHttpAuthExpired,
                    )
                },
                createFileDownloads = { session ->
                    AndroidFileDownloadController(
                        context = context.applicationContext,
                        mediaSession = session,
                        uiScope = uiScope,
                        telemetry = dataState.telemetry,
                        telemetryPage = ClientUiPage.TASKS,
                        requestExportPermission = requestExportPermission,
                    )
                },
            )
        }
    }
    val media = mediaLease.resourceOrNull() ?: return
    DisposableEffect(mediaLease) { onDispose { mediaLease.close() } }
    val editorKey = feature.materialsEditorKey
    val selector = remember(media, editorKey) { AndroidEmbeddedAssetSelector() }
    val continuation = rememberSaveable(dataState, editorKey, saver = AndroidEmbeddedAssetPickerContinuation.Saver) {
        AndroidEmbeddedAssetPickerContinuation()
    }
    continuation.snapshot()?.takeUnless { it.belongsTo("task:$editorKey") }?.let(continuation::clear)
    val imports = remember(media, editorKey, continuation) {
        AndroidEmbeddedAssetImportGateway(
            context = context.applicationContext,
            mediaSession = media.mediaSession,
            selector = selector,
            pickerContinuation = continuation,
            launchAdmittedAction = launchAdmittedAction,
            launchCancellableAdmittedAction = { action -> dataState.launchCancellableAdmittedUiAction(action = action) },
            deliverIfOpen = dataState.uiActionAdmission::runIfOpen,
        )
    }
    DisposableEffect(imports) { onDispose { imports.close() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        imports.completePicker(EmbeddedAssetPresentation.FILE, uri)
    }
    SideEffect { selector.pickFile = { picker.launch(arrayOf("*/*")) } }
    val materials = remember(dataState, media, imports, onOpenDocument) {
        TaskMaterialsUi(
            imports = imports,
            fileDownloads = requireNotNull(media.fileDownloads),
            loadDocuments = dataState.tasks::loadMaterialDocumentCandidates,
            openDocument = { reference ->
                dataState.tasks.openMaterialDocument(reference) {
                    dataState.uiActionAdmission.runIfOpen { onOpenDocument(reference) }
                }
            },
        )
    }
    TaskWorkspaceScreen(
        feature = dataState.tasks,
        actionAdmission = dataState.uiActionAdmission,
        materials = materials,
        compactMode = true,
        modifier = Modifier.imePadding(),
    )
}
