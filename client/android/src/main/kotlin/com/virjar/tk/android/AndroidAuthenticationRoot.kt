package com.virjar.tk.android

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.app.client.AuthState
import com.virjar.tk.app.client.AuthFormSubmissionState
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ServerConfig
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.createAndroidLocalCache
import com.virjar.tk.app.client.rememberAuthController
import com.virjar.tk.app.ui.screen.LoginScreen
import com.virjar.tk.app.ui.screen.RegisterScreen
import com.virjar.tk.app.ui.component.forcedProtocolUpgradeMessage
import kotlinx.coroutines.launch

/** 认证/离线所有者外壳；已认证的导航图单独存放。 */
@Composable
internal fun AndroidAppRoot(
    applicationContext: Context,
    serverConfig: ServerConfig,
    appDataStateHolder: AndroidAppDataStateHolder,
    beforeSessionRetirement: (ClientSession, SessionEndReason) -> Unit,
    onProtocolUpgradeExit: () -> Unit,
) {
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
    val uiScope = rememberCoroutineScope()
    val auth = rememberAuthController(
        tokenStore = tokenStore,
        deploymentIdentity = deploymentIdentity,
        tcpHost = deploymentIdentity.tcpHost,
        tcpPort = deploymentIdentity.tcpPort,
        deviceId = deviceId,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceFlag = AuthRules.DEVICE_FLAG_ANDROID,
        createCache = { identity, datasetId, uid ->
            createAndroidLocalCache(applicationContext, identity, datasetId, uid)
        },
        beforeSessionRetirement = beforeSessionRetirement,
        runtimeInfo = remember { androidClientRuntimeInfo() },
        telemetrySpoolRoot = applicationContext.filesDir,
    )
    val sessionSnapshot = auth.session
    when (
        androidAuthenticationSurface(
            hasLocalSession = auth.hasLocalSession,
            hasActiveSession = sessionSnapshot?.isBusinessActive == true,
            autoLoggingIn = auth.autoLoggingIn,
            requiresProtocolUpgrade = auth.requiresProtocolUpgrade,
        )
    ) {
        AndroidAuthenticationSurface.PROTOCOL_UPGRADE -> Box(Modifier.fillMaxSize())
        AndroidAuthenticationSurface.LOADING -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        AndroidAuthenticationSurface.LOGIN -> AuthFlow(auth)
        AndroidAuthenticationSurface.AUTHENTICATED -> {
            val authenticatedSession = sessionSnapshot
            if (authenticatedSession == null) {
                AuthFlow(auth)
            } else {
                val connectionState by auth.connectionState.collectAsState()
                val uiSession = remember(authenticatedSession) {
                    appDataStateHolder.forSession(
                        session = authenticatedSession,
                        onAuthExpired = {
                            uiScope.launch {
                                appDataStateHolder.runIfSessionOwner(authenticatedSession) {
                                    auth.onAuthExpiredForSession(authenticatedSession)
                                }
                            }
                        },
                        onHttpAuthExpired = { rejectedAccessToken ->
                            uiScope.launch {
                                appDataStateHolder.runIfSessionOwner(authenticatedSession) {
                                    auth.onHttpAuthExpiredForSession(
                                        authenticatedSession,
                                        rejectedAccessToken,
                                    )
                                }
                            }
                        },
                    )
                }
                RequestAndroidMessageNotificationPermission()
                AndroidMainApp(
                    dataState = uiSession.dataState,
                    resourceOwner = uiSession.resourceOwner,
                    connectionState = connectionState,
                    protocolCompatibility = auth.protocolCompatibility,
                    notificationNavigation = appDataStateHolder.notificationNavigation,
                    onLogout = {
                        appDataStateHolder.runIfSessionOwner(authenticatedSession) {
                            auth.onLogoutForSession(authenticatedSession)
                        }
                    },
                )
            }
        }
    }
    if (auth.requiresProtocolUpgrade) {
        ProtocolUpgradeDialog(
            onExit = onProtocolUpgradeExit,
            message = forcedProtocolUpgradeMessage(auth.protocolCompatibility),
        )
    }
}

internal enum class AndroidAuthenticationSurface { LOADING, LOGIN, AUTHENTICATED, PROTOCOL_UPGRADE }

internal fun androidAuthenticationSurface(
    hasLocalSession: Boolean,
    hasActiveSession: Boolean,
    autoLoggingIn: Boolean,
    requiresProtocolUpgrade: Boolean = false,
): AndroidAuthenticationSurface = when {
    requiresProtocolUpgrade -> AndroidAuthenticationSurface.PROTOCOL_UPGRADE
    hasLocalSession && hasActiveSession -> AndroidAuthenticationSurface.AUTHENTICATED
    autoLoggingIn -> AndroidAuthenticationSurface.LOADING
    else -> AndroidAuthenticationSurface.LOGIN
}

@Composable
private fun AuthFlow(auth: AuthState) {
    var destination by remember { mutableStateOf(AuthDestination.LOGIN) }
    val loginSubmission = remember { AuthFormSubmissionState() }
    val registerSubmission = remember { AuthFormSubmissionState() }
    val connectionState by auth.connectionState.collectAsState()
    LaunchedEffect(connectionState) {
        loginSubmission.onConnectionStateChanged(connectionState)
        registerSubmission.onConnectionStateChanged(connectionState)
    }
    val backDestination = destination.backDestination()
    BackHandler(enabled = backDestination != null) {
        backDestination?.let { destination = it }
        auth.clearError()
    }
    if (destination == AuthDestination.REGISTER) {
        RegisterScreen(
            onRegister = { username, password, name ->
                registerSubmission.submit { auth.onRegister(username, password, name) }
            },
            onNavigateBack = {
                destination = AuthDestination.LOGIN
                auth.clearError()
            },
            error = auth.authError,
            loading = registerSubmission.loading,
        )
    } else {
        LoginScreen(
            onLogin = { username, password ->
                loginSubmission.submit { auth.onLogin(username, password) }
            },
            onNavigateToRegister = {
                destination = AuthDestination.REGISTER
                auth.clearError()
            },
            error = auth.authError,
            loading = loginSubmission.loading,
        )
    }
}

internal enum class AuthDestination {
    LOGIN,
    REGISTER,
    ;

    fun backDestination(): AuthDestination? = when (this) {
        LOGIN -> null
        REGISTER -> LOGIN
    }
}
