@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.virjar.tk.app.client.*
import com.virjar.tk.app.ui.AppTheme
import com.virjar.tk.app.ui.component.AccountBanSurface
import com.virjar.tk.app.ui.screen.LoginScreen
import com.virjar.tk.app.ui.screen.RegisterScreen
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.telemetry.ClientPlatform
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.shared.client.*
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.*

fun MainViewController(): UIViewController = ComposeUIViewController {
    AppTheme(touchDensity = true) { IosAppRoot() }
}.also { IosApplicationRuntime.rootViewController = it }

internal data class IosNotificationTarget(val chatId: String, val deployment: String, val dataset: String, val uid: String)
internal data class IosPushToken(val token: String, val environment: String)

/** Swift delegates application events only; authenticated business ownership stays in Kotlin. */
object IosApplicationRuntime {
    internal var rootViewController: UIViewController? = null
    internal val foreground = MutableStateFlow(false)
    internal val pushToken = MutableStateFlow<IosPushToken?>(null)
    internal val notification = MutableStateFlow<IosNotificationTarget?>(null)
    internal var sessionUi: IosSessionUi? = null
    internal val draftPersistence = IosDocumentDraftPersistence()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backgroundDraftFlush: Job? = null

    fun didBecomeActive() { foreground.value = true }
    fun didEnterBackground() {
        foreground.value = false
        sessionUi?.let { ui ->
            ui.recorder.close()
            ui.voice.close()
            try { ui.captureDrafts() }
            catch (failure: Throwable) { logUnhandledError("IosBackgroundDraft", failure) }
        }
        // Capture stays synchronous with the editor frame. Persistence owns no UIKit/SDK objects
        // and may finish during the finite background interval without blocking this callback.
        backgroundDraftFlush?.cancel()
        val completion = draftPersistence.requestFlush()
        val application = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        var observer: Job? = null
        fun finish() {
            val current = task
            task = UIBackgroundTaskInvalid
            if (current != UIBackgroundTaskInvalid) application.endBackgroundTask(current)
        }
        task = application.beginBackgroundTaskWithName("Document drafts") {
            observer?.cancel()
            finish()
        }
        observer = lifecycleScope.launch {
            try { check(completion.await()) { "Document draft persistence failed" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { logUnhandledError("IosBackgroundDraft", failure) }
            finally { finish() }
        }
        backgroundDraftFlush = observer
    }

    internal fun observeDraftFlush(tag: String) {
        val completion = draftPersistence.requestFlush()
        lifecycleScope.launch {
            try { check(completion.await()) { "Document draft persistence failed" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { logUnhandledError(tag, failure) }
        }
    }
    fun registerPushToken(token: String, environment: String) {
        if (token.length !in 32..512 || token.any { it !in '0'..'9' && it !in 'a'..'f' } ||
            environment !in setOf("sandbox", "production")) return
        pushToken.value = IosPushToken(token, environment)
    }
    fun acceptsNotification(deploymentFingerprint: String, datasetId: String, uid: String): Boolean {
        val ui = sessionUi ?: return false
        return ui.data.acceptsRendering && ui.session.deploymentIdentity.fingerprint == deploymentFingerprint &&
            ui.session.datasetId == datasetId && ui.session.ownerUid == uid
    }
    fun openNotification(chatId: String, deploymentFingerprint: String, datasetId: String, uid: String) {
        if (chatId.isBlank() || chatId.length > 128) return
        // Keep cold-start clicks until an authenticated account can verify all three owner coordinates.
        notification.value = IosNotificationTarget(chatId, deploymentFingerprint, datasetId, uid)
    }
    internal fun retire(session: ClientSession, reason: SessionEndReason) = onIosMain {
        val current = sessionUi?.takeIf { it.session === session } ?: return@onIosMain
        sessionUi = null
        notification.value = null
        current.close(reason)
    }
}

@Composable
private fun IosAppRoot() {
    val config = remember { ServerConfig(ClientBuildConfig.SERVER_BASE_URL, ClientBuildConfig.TCP_HOST,
        ClientBuildConfig.TCP_PORT, decodeTcpTlsCertificateBase64(ClientBuildConfig.TCP_TLS_CERTIFICATE_BASE64)) }
    val identity = remember(config) { config.deploymentIdentity() }
    var booted by remember { mutableStateOf(false) }
    var bootFailure by remember { mutableStateOf(false) }
    var bootAttempt by remember { mutableIntStateOf(0) }
    var tokenStore by remember { mutableStateOf<IosTokenStore?>(null) }
    var installationDeviceId by remember { mutableStateOf<String?>(null) }
    val cleanup = remember { iosAccountDataCleanup() }
    LaunchedEffect(bootAttempt) {
        bootFailure = false
        try {
            val boot = withContext(Dispatchers.IO) {
                prepareIosClientDataVersion()
                clearIosAbandonedStaging()
                val store = IosTokenStore(identity).also { resumePendingAccountCleanup(cleanup, it::clearBannedAccount) }
                store to store.deviceId
            }
            tokenStore = boot.first; installationDeviceId = boot.second; booted = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { logUnhandledError("IosStartup", failure); bootFailure = true }
    }
    if (!booted) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            if (bootFailure) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("本地数据准备失败，请重试")
                Button(onClick = { bootAttempt++ }) { Text("重试") }
            } else CircularProgressIndicator()
        }
        return
    }
    val deviceId = checkNotNull(installationDeviceId)
    val auth = rememberAuthController(
        tokenStore = checkNotNull(tokenStore), deploymentIdentity = identity, tcpHost = config.tcpHost,
        tcpPort = config.tcpPort, tcpTlsCertificatePem = config.tcpTlsCertificatePem,
        deviceId = deviceId, deviceName = "iOS", deviceModel = UIDevice.currentDevice.model,
        deviceFlag = 3, createCache = { deployment, dataset, uid -> createIosLocalCache(deployment, dataset, uid) },
        beforeSessionRetirement = IosApplicationRuntime::retire,
        beforeAccountDataCleanup = {
            // Account cleanup must never race an already admitted process-owned disk write.
            check(IosApplicationRuntime.draftPersistence.awaitQuiescence()) { "Document drafts have not settled" }
        },
        runtimeInfo = remember { iosRuntimeInfo() }, accountDataCleanup = cleanup,
    )
    val connection by auth.connectionState.collectAsState()
    val session = auth.session
    val composeScope = rememberCoroutineScope()
    when {
        auth.accountBanState != null -> AccountBanSurface(checkNotNull(auth.accountBanState), auth.dismissAccountBan,
            { showIosError("本地清理尚未完成，请从应用切换器关闭并重新打开应用") }, exitActionLabel = "查看重新打开方式")
        auth.requiresProtocolUpgrade -> Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("当前版本不支持服务器协议，请安装组织提供的新版本")
                Button(onClick = { openIosUrl(config.serverUrl) }) { Text("打开部署站点") }
            }
        }
        session != null -> {
            val ui = remember(session) {
                IosSessionUi(session, IosApplicationRuntime.draftPersistence,
                    onAuthExpired = { composeScope.launch { auth.onAuthExpiredForSession(session) } },
                    onHttpAuthExpired = { rejected -> composeScope.launch { auth.onHttpAuthExpiredForSession(session, rejected) } })
                    .also { IosApplicationRuntime.sessionUi = it }
            }
            DisposableEffect(ui) {
                onDispose { IosApplicationRuntime.retire(session, SessionEndReason.PROCESS_REPLACED) }
            }
            IosPushRegistration(ui, connection, auth.protocolCompatibility)
            IosMainContent(ui, connection, auth.protocolCompatibility) {
                ui.data.launchAdmittedUiAction {
                    try {
                        IosApplicationRuntime.pushToken.value?.takeIf {
                            (auth.protocolCompatibility?.negotiated?.minor ?: 0) >= 4
                        }?.let { registration ->
                            withTimeoutOrNull(3_000) {
                                session.deviceRepo.setApnsPushRegistration("", ClientBuildConfig.IOS_BUNDLE_ID,
                                    registration.environment, session.deploymentIdentity.fingerprint)
                            }
                        }
                    } finally { auth.onLogoutForSession(session) }
                }
            }
        }
        auth.autoLoggingIn -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> {
            var register by remember { mutableStateOf(false) }
            val form = remember { AuthFormSubmissionState() }
            LaunchedEffect(connection, auth.authError) { form.onConnectionStateChanged(connection) }
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                if (register) RegisterScreen(
                    onRegister = { username, password, name -> form.submit { auth.onRegister(username, password, name) } },
                    onNavigateBack = { register = false; auth.clearError() }, error = auth.authError, loading = form.loading)
                else LoginScreen(
                    onLogin = { username, password -> form.submit { auth.onLogin(username, password) } },
                    onNavigateToRegister = { register = true; auth.clearError() }, error = auth.authError, loading = form.loading,
                    serverUrl = config.serverUrl)
            }
        }
    }
}

@Composable
private fun IosPushRegistration(ui: IosSessionUi, connection: ConnectionState, compatibility: ProtocolCompatibility?) {
    val registration by IosApplicationRuntime.pushToken.collectAsState()
    LaunchedEffect(ui, registration, connection, compatibility) {
        val token = registration ?: return@LaunchedEffect
        if (connection != ConnectionState.AUTHENTICATED || (compatibility?.negotiated?.minor ?: 0) < 4) return@LaunchedEffect
        while (isActive && ui.data.acceptsRendering) {
            val result = ui.session.deviceRepo.setApnsPushRegistration(token.token, ClientBuildConfig.IOS_BUNDLE_ID,
                token.environment, ui.session.deploymentIdentity.fingerprint)
            if (result.getOrNull() == true) break
            if (result is Outcome.Failure && result.error == AppError.AuthExpired) {
                ui.data.reportAuthExpired()
                break
            }
            delay(30_000)
        }
    }
}

private fun iosRuntimeInfo() = ClientRuntimeInfo(
    // Released HTTP telemetry schemas do not know an IOS enum value. Keep its extensible fields precise.
    platform = ClientPlatform.UNKNOWN, osName = "iOS", osVersion = UIDevice.currentDevice.systemVersion,
    architecture = "arm64", deviceModel = UIDevice.currentDevice.model,
    appVersion = ClientBuildConfig.APP_VERSION, buildNumber = ClientBuildConfig.BUILD_NUMBER.toString(),
    gitCommit = ClientBuildConfig.GIT_COMMIT_ID, buildIdentity = ClientBuildConfig.BUILD_IDENTITY,
    buildTime = ClientBuildConfig.BUILD_TIME, protocolVersion = ProtocolVersions.CURRENT_ID, distribution = "ios",
)
