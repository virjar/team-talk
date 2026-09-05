package com.virjar.tk.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.bridge.EmbeddedAssetMediaConfig
import com.virjar.tk.app.ui.screen.DocumentWorkspaceHost
import com.virjar.tk.app.ui.screen.MobileDocumentExitCoordinator

/** Android Home 文档路由，拥有真实的已认证选择器/上传/渲染/下载所有权。 */
@Composable
internal fun AndroidDocumentWorkspaceHost(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
    mobileExitCoordinator: MobileDocumentExitCoordinator,
    onExitDocuments: () -> Unit,
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val mediaLease = remember(
        context.applicationContext,
        dataState,
        resourceOwner,
        uiScope,
    ) {
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
                createFileDownloads = { mediaSession ->
                    AndroidFileDownloadController(
                        context = context.applicationContext,
                        mediaSession = mediaSession,
                        uiScope = uiScope,
                        telemetry = dataState.telemetry,
                        telemetryPage = ClientUiPage.DOCUMENTS,
                    )
                },
            )
        }
    }
    val mediaResources = mediaLease.resourceOrNull() ?: return
    val fileDownloads = requireNotNull(mediaResources.fileDownloads)
    val selector = remember(mediaResources) { AndroidEmbeddedAssetSelector() }
    val pickerContinuation = rememberSaveable(
        dataState.deploymentIdentity.fingerprint,
        dataState.datasetId,
        dataState.userSession.uid,
        saver = AndroidEmbeddedAssetPickerContinuation.Saver,
    ) { AndroidEmbeddedAssetPickerContinuation() }
    val imports = remember(mediaResources, selector, dataState, pickerContinuation) {
        AndroidEmbeddedAssetImportGateway(
            context = context.applicationContext,
            mediaSession = mediaResources.mediaSession,
            selector = selector,
            pickerContinuation = pickerContinuation,
            launchAdmittedAction = launchAdmittedAction,
            launchCancellableAdmittedAction = { action ->
                dataState.launchCancellableAdmittedUiAction(action = action)
            },
            deliverIfOpen = dataState.uiActionAdmission::runIfOpen,
        )
    }
    DisposableEffect(imports, mediaLease) {
        onDispose {
            imports.close()
            mediaLease.close()
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        imports.completePicker(EmbeddedAssetPresentation.FILE, uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        imports.completePicker(EmbeddedAssetPresentation.IMAGE, uri)
    }
    SideEffect {
        selector.pickImage = {
            imagePicker.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build(),
            )
        }
        selector.pickFile = { filePicker.launch(arrayOf("*/*")) }
    }
    val media = remember(context.applicationContext, mediaResources, fileDownloads, imports) {
        EmbeddedAssetMediaConfig(
            fileDownloads = fileDownloads,
            imageContent = { attachment, modifier ->
                rememberAsyncThumb(
                    attachment = attachment,
                    mediaSession = mediaResources.mediaSession,
                    modifier = modifier,
                    placeholderColor = android.graphics.Color.LTGRAY,
                )
            },
            onPasteEmbeddedAsset = {
                importAndroidClipboardAsset(context.applicationContext, imports)
            },
        )
    }

    DocumentWorkspaceHost(
        workspace = dataState.documents,
        actionAdmission = dataState.uiActionAdmission,
        mobileSingleDocumentMode = true,
        mobileExitCoordinator = mobileExitCoordinator,
        embeddedAssetImports = imports,
        embeddedAssetMedia = media,
        onExitDocuments = onExitDocuments,
    )
}
