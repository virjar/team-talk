package com.virjar.tk.android

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.virjar.tk.app.client.AuthState
import com.virjar.tk.app.client.rememberAuthController
import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ServerConfig
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.createAndroidLocalCache

/**
 * Android 对共享认证控制器的适配：设备身份、凭据、缓存工厂和账号清理钩子在此一起组装。
 * 认证及封禁状态仍只由共享控制器持有；[AndroidAppRoot] 仅据其结果选择界面。
 * Application 的 pending 恢复必须先于本函数，不能用创建新控制器绕过失败的启动清理。
 */
@Composable
internal fun rememberAndroidAuthentication(
    applicationContext: Context,
    serverConfig: ServerConfig,
    beforeSessionRetirement: (ClientSession, SessionEndReason) -> Unit,
    afterSessionRetirement: (ClientSession, SessionEndReason) -> Unit = { _, _ -> },
): AuthState {
    val application = applicationContext.applicationContext as TeamTalkApp
    val deploymentIdentity = remember(serverConfig) { serverConfig.deploymentIdentity() }
    val tokenStore = remember(deploymentIdentity) {
        TokenStore(applicationContext, deploymentIdentity)
    }
    val deviceId = remember { AndroidDeviceIdentity.getOrCreate(applicationContext) }
    val deviceName = remember {
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            .takeIf { AuthRules.validateDeviceName(it) == null }
            ?: "Android"
    }
    val deviceModel = remember {
        Build.MODEL.takeIf { AuthRules.validateDeviceModel(it) == null }
    }
    return rememberAuthController(
        tokenStore = tokenStore,
        deploymentIdentity = deploymentIdentity,
        tcpHost = deploymentIdentity.tcpHost,
        tcpPort = deploymentIdentity.tcpPort,
        tcpTlsCertificatePem = serverConfig.tcpTlsCertificatePem,
        deviceId = deviceId,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceFlag = AuthRules.DEVICE_FLAG_ANDROID,
        createCache = { identity, datasetId, uid ->
            createAndroidLocalCache(applicationContext, identity, datasetId, uid)
        },
        beforeSessionRetirement = beforeSessionRetirement,
        afterSessionRetirement = afterSessionRetirement,
        runtimeInfo = remember { androidClientRuntimeInfo() },
        telemetrySpoolRoot = applicationContext.filesDir,
        accountDataCleanup = remember(application) { androidAccountDataCleanup(application) },
        beforeAccountDataCleanup = { owner ->
            application.documentDraftPersistence.discardBannedAccountDrafts(owner)
        },
    )
}
