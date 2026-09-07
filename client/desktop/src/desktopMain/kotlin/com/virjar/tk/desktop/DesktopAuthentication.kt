package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.virjar.tk.app.client.AuthState
import com.virjar.tk.app.client.rememberAuthController
import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.shared.client.ServerConfig
import com.virjar.tk.shared.client.createDesktopLocalCache
import java.io.File
import java.util.UUID

/**
 * Desktop 的认证适配器，与 Android 保持相同的共享控制器入口。
 * 窗口负责部署切换和会话 UI 的挂载；此处集中组装设备身份、凭据、缓存和清理挂钩。
 * 草稿/媒体的退役沿用 [DesktopAuthenticatedUiRetirementBridge]，不额外持有封禁状态。
 */
@Composable
internal fun rememberDesktopAuthentication(
    dataDir: File,
    config: ServerConfig,
    sessionRetirement: DesktopAuthenticatedUiRetirementBridge,
): AuthState {
    val deploymentIdentity = remember(config) { config.deploymentIdentity() }
    val tokenStore = remember(deploymentIdentity) { DesktopTokenStore(dataDir, deploymentIdentity) }
    val deviceId = remember(dataDir) { desktopInstallationDeviceId(dataDir) }
    return rememberAuthController(
        tokenStore = tokenStore,
        deploymentIdentity = deploymentIdentity,
        tcpHost = config.tcpHost,
        tcpPort = config.tcpPort,
        tcpTlsCertificatePem = config.tcpTlsCertificatePem,
        deviceId = deviceId,
        deviceName = "Desktop",
        deviceModel = System.getProperty("os.name")
            ?.takeIf { AuthRules.validateDeviceModel(it) == null },
        deviceFlag = AuthRules.DEVICE_FLAG_DESKTOP,
        createCache = { identity, datasetId, uid ->
            createDesktopLocalCache(identity, datasetId, uid, dataDir)
        },
        beforeSessionRetirement = sessionRetirement::beforeSessionRetirement,
        afterSessionRetirement = sessionRetirement::afterSessionRetirement,
        runtimeInfo = remember { desktopClientRuntimeInfo() },
        telemetrySpoolRoot = dataDir,
        accountDataCleanup = remember(dataDir) { desktopAccountDataCleanup(dataDir) },
    )
}

/** 每个 Desktop 数据目录一个持久设备身份；进程重启或同安装切换服务器时复用。 */
internal fun desktopInstallationDeviceId(dataDir: File): String {
    val identityStore = JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(fileName = "device-id")
    identityStore.readText(MAX_DEVICE_ID_FILE_BYTES)?.trim()?.let { stored ->
        require(AuthRules.validateDeviceId(stored) == null) { "Stored Desktop device identity is invalid" }
        return stored
    }
    val generated = "desktop-${UUID.randomUUID()}"
    identityStore.replaceText(generated, MAX_DEVICE_ID_FILE_BYTES)
    check(identityStore.readText(MAX_DEVICE_ID_FILE_BYTES) == generated) {
        "Desktop device identity was not persisted"
    }
    return generated
}

private const val MAX_DEVICE_ID_FILE_BYTES = 1024L
