package com.virjar.tk.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.virjar.tk.app.navigation.AppDataState

/** 已认证的、应用作用域内的聊天附件选择与上传所有者。 */
internal data class AndroidChatEmbeddedAssetImportHost(
    val gateway: AndroidEmbeddedAssetImportGateway,
    val selector: AndroidEmbeddedAssetSelector,
)

/**
 * 让富媒体资源网关挂接在已认证的外壳上，而不是某个单独的聊天页上。
 * 因此上传完成结果可以在路由变化之后重放给发起上传的聊天页，
 * 而会话销毁仍然会关闭网关及其媒体资源租约。
 */
@Composable
internal fun rememberAndroidChatEmbeddedAssetImportHost(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
): AndroidChatEmbeddedAssetImportHost? {
    val applicationContext = LocalContext.current.applicationContext
    val selector = remember(resourceOwner) { AndroidEmbeddedAssetSelector() }
    val pickerContinuation = rememberSaveable(
        dataState.deploymentIdentity.fingerprint,
        dataState.datasetId,
        dataState.userSession.uid,
        saver = AndroidEmbeddedAssetPickerContinuation.Saver,
    ) { AndroidEmbeddedAssetPickerContinuation() }
    val mediaLease = remember(applicationContext, dataState, resourceOwner) {
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
            )
        }
    }
    val mediaResources = mediaLease.resourceOrNull() ?: return null
    val gateway = remember(applicationContext, dataState, mediaResources, selector, pickerContinuation) {
        AndroidEmbeddedAssetImportGateway(
            context = applicationContext,
            mediaSession = mediaResources.mediaSession,
            selector = selector,
            pickerContinuation = pickerContinuation,
            launchAdmittedAction = { action -> dataState.launchAdmittedUiAction(action = action) },
            launchCancellableAdmittedAction = { action ->
                dataState.launchCancellableAdmittedUiAction(action = action)
            },
            deliverIfOpen = dataState.uiActionAdmission::runIfOpen,
        )
    }
    DisposableEffect(gateway, mediaLease) {
        onDispose {
            gateway.close()
            mediaLease.close()
        }
    }
    return remember(gateway, selector) {
        AndroidChatEmbeddedAssetImportHost(gateway = gateway, selector = selector)
    }
}
