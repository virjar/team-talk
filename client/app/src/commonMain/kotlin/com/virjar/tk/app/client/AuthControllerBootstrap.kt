package com.virjar.tk.app.client

import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.TokenStore
import com.virjar.tk.shared.client.AuthenticationAttemptAdmission
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.logUnhandledError
import com.virjar.tk.shared.client.platformDataDir
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

private val authCredentialOwnerClaims = AuthCredentialOwnerClaimCoordinator()

private sealed interface AuthCredentialOwnerBootstrapState {
    data object Loading : AuthCredentialOwnerBootstrapState

    data class Ready(val owner: AuthControllerCredentialOwner) : AuthCredentialOwnerBootstrapState

    data object Failed : AuthCredentialOwnerBootstrapState
}

internal class AuthControllerLifetime {
    var isActive: Boolean = true
        private set

    fun retire() {
        isActive = false
    }
}

/**
 * 跨平台认证控制器（UI 层 Compose 包装）。
 *
 * 分层说明：本文件是 app/UI 层唯一的 Compose 认证入口；认证的底层能力
 * （ImClient 连接、createSession、UserSession 三级状态）在 shared（IM SDK）。
 * 包名保持 `com.virjar.tk.shared.client` 以兼容既有 import。
 *
 * 封装 Android/Desktop 重复的认证状态机 + 三级状态管理：
 *
 * 1. 创建 [UserSession]（用户层）+ [ImClient]（连接层，注入认证回调）
 * 2. 启动时检查持久凭据，先恢复固定 uid 的本地 session，再在后台连接并认证
 * 3. 本地 session 建立后立即开放业务 UI；没有网络时页面继续观察 LocalCache
 * 4. 身份认证成功后确认稳定 refresh、安装新 access，由 EventProcessor 分页同步并收敛缓存
 * 5. AUTH_FAILED 按类型处理：权威撤销清除身份，可重试失败保留本地会话并显示离线状态
 * 6. onLogout 时清理 session + 清除 token
 *
 * ImClient 的认证结果通过回调写入 UserSession（三级状态隔离），
 * UserSession 生命周期独立于 TCP 连接。
 *
 * @param tokenStore 登录态持久化
 * @param tcpHost TCP 主机
 * @param tcpPort TCP 端口
 * @param deviceId 设备 ID（如 "android-device" / "desktop-device"）
 * @param deviceName 设备名（如 "Android" / "Desktop"）
 * @param createCache 平台 LocalCache 工厂
 * @param onAuthenticated 认证成功后的额外本地投影回调（如 Android 的 upsertUser）；在会话构造
 * dispatcher 上执行，不得直接写 Compose/UI 状态。
 * @param beforeSessionRetirement 平台认证 UI 的同步前置边界。只在已经存在 [ClientSession]
 * 的终止路径触发，必须在返回前停止所有仍会借用 cache/HTTP bearer 的平台 owner。
 * @param afterSessionRetirement 与前置边界严格 try/finally 配对；平台可据此唤醒等待同一
 * session 终止完成的并发 follower。
 */
@Composable
fun rememberAuthController(
    tokenStore: TokenStore,
    deploymentIdentity: DeploymentIdentity,
    tcpHost: String,
    tcpPort: Int,
    deviceId: String,
    deviceName: String,
    deviceModel: String? = null,
    deviceFlag: Int = 0,
    createCache: (deploymentIdentity: DeploymentIdentity, datasetId: String, uid: String) -> LocalCache,
    onAuthenticated: ((ClientSession) -> Unit)? = null,
    beforeSessionRetirement: (ClientSession, SessionEndReason) -> Unit = { _, _ -> },
    afterSessionRetirement: (ClientSession, SessionEndReason) -> Unit = { _, _ -> },
    runtimeInfo: ClientRuntimeInfo = ClientRuntimeInfo.unknown(),
    telemetrySpoolRoot: File = platformDataDir(),
): AuthState {
    val authenticationAttempts = remember(tokenStore, deploymentIdentity, tcpHost, tcpPort) {
        AuthenticationAttemptAdmission()
    }
    // 预留只是内存操作。claimOwner 执行的 SharedPreferences/properties 比较并交换（CAS）
    // 刻意不放在组合 dispatcher 上，而且即使更早的阻塞式认领已经在进行中，
    // 较新的根仍然胜出。
    val ownerClaimLease = remember(
        tokenStore,
        deploymentIdentity,
        tcpHost,
        tcpPort,
        authenticationAttempts,
    ) {
        authCredentialOwnerClaims.reserve(tokenStore.ownerClaimNamespace)
    }
    var bootstrapState by remember(ownerClaimLease) {
        mutableStateOf<AuthCredentialOwnerBootstrapState>(AuthCredentialOwnerBootstrapState.Loading)
    }
    DisposableEffect(ownerClaimLease) {
        onDispose { ownerClaimLease.close() }
    }
    LaunchedEffect(ownerClaimLease) {
        try {
            when (
                val result = authCredentialOwnerClaims.claim(
                    lease = ownerClaimLease,
                    blockingDispatcher = Dispatchers.IO,
                    blockingClaim = {
                        AuthControllerCredentialOwner.claim(
                            tokenStore = tokenStore,
                            deploymentIdentity = deploymentIdentity,
                            tcpHost = tcpHost,
                            tcpPort = tcpPort,
                            authenticationAttempts = authenticationAttempts,
                        )
                    },
                )
            ) {
                is AuthCredentialOwnerClaimResult.Claimed -> {
                    bootstrapState = AuthCredentialOwnerBootstrapState.Ready(result.owner)
                }
                AuthCredentialOwnerClaimResult.Superseded -> Unit
            }
        } catch (failure: Throwable) {
            ownerClaimLease.close()
            if (isFatalClientLifecycleFailure(failure)) throw failure
            logUnhandledError("CredentialOwnerBootstrap", failure)
            bootstrapState = AuthCredentialOwnerBootstrapState.Failed
        }
    }

    val credentialOwner = (bootstrapState as? AuthCredentialOwnerBootstrapState.Ready)?.owner
        ?: return rememberCredentialOwnerBootstrapAuthState(
            failed = bootstrapState == AuthCredentialOwnerBootstrapState.Failed,
        )
    return rememberClaimedAuthController(
        deploymentIdentity = deploymentIdentity,
        tcpHost = tcpHost,
        tcpPort = tcpPort,
        deviceId = deviceId,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceFlag = deviceFlag,
        createCache = createCache,
        onAuthenticated = onAuthenticated,
        beforeSessionRetirement = beforeSessionRetirement,
        afterSessionRetirement = afterSessionRetirement,
        authenticationAttempts = authenticationAttempts,
        credentialOwner = credentialOwner,
        ownerClaimLease = ownerClaimLease,
        sessionConstructionDispatcher = Dispatchers.IO,
        runtimeInfo = runtimeInfo,
        telemetrySpoolRoot = telemetrySpoolRoot,
    )
}

@Composable
private fun rememberCredentialOwnerBootstrapAuthState(failed: Boolean): AuthState {
    val connectionState = remember { MutableStateFlow(ConnectionState.DISCONNECTED) }
    return remember(failed, connectionState) {
        AuthState(
            autoLoggingIn = !failed,
            authError = if (failed) "本地登录状态读取失败，请重启应用" else null,
            requiresProtocolUpgrade = false,
            session = null,
            connectionState = connectionState,
            onLogin = { _, _ -> AuthSubmissionDisposition.STALE },
            onRegister = { _, _, _ -> AuthSubmissionDisposition.STALE },
            onLogout = {},
            onAuthExpired = {},
            onLogoutForSession = { false },
            onAuthExpiredForSession = { false },
            onHttpAuthExpiredForSession = { _, _ -> false },
            clearError = {},
        )
    }
}
