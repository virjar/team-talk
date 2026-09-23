package com.virjar.tk.ios

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.telemetry.*
import com.virjar.tk.app.ui.bridge.*
import com.virjar.tk.app.ui.component.*
import com.virjar.tk.app.ui.platform.LocalIosBackDispatcher
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ProtocolCompatibility
import com.virjar.tk.shared.platform.platformMonotonicNanos
import kotlinx.coroutines.*

@Composable
internal fun IosMainContent(ui: IosSessionUi, connection: ConnectionState,
    compatibility: ProtocolCompatibility?, onLogout: () -> Unit) {
    val data = ui.data
    if (!data.acceptsRendering) return
    val route = ui.navigation.current
    val edgeWidth = with(LocalDensity.current) { 24.dp.toPx() }
    val backDistance = with(LocalDensity.current) { 80.dp.toPx() }
    val foreground by IosApplicationRuntime.foreground.collectAsState()
    val target by IosApplicationRuntime.notification.collectAsState()
    val snackbar = remember(ui) { SnackbarHostState() }
    val eventOwner = remember(ui) { Any() }
    LaunchedEffect(data.errorSignal) {
        while (true) {
            val error = data.acquireError(eventOwner) ?: break
            try { data.markErrorDisplayed(error); snackbar.showSnackbar(error.value); data.completeError(error) }
            finally { data.releaseError(error) }
        }
    }
    LaunchedEffect(data.noticeSignal) {
        while (true) {
            val notice = data.acquireNotice(eventOwner) ?: break
            try {
                if (data.markNoticeDisplayed(notice)) data.telemetry.recordUserNotice(notice.value)
                snackbar.showSnackbar(notice.value.publicMessage); data.completeNotice(notice)
            } finally { data.releaseNotice(notice) }
        }
    }
    LaunchedEffect(target, connection, ui) {
        val click = target ?: return@LaunchedEffect
        if (!IosApplicationRuntime.acceptsNotification(click.deployment, click.dataset, click.uid)) {
            IosApplicationRuntime.notification.compareAndSet(click, null)
            return@LaunchedEffect
        }
        if (connection != ConnectionState.AUTHENTICATED) return@LaunchedEffect
        if (data.chat.prepareChat(click.chatId)) {
            ui.navigation.home(MainTab.CONVERSATIONS)
            ui.navigation.chat(click.chatId)
        }
        IosApplicationRuntime.notification.compareAndSet(click, null)
    }
    LaunchedEffect(connection) { data.telemetry.recordSystem(ClientSystemEvent.CONNECTION_STATE, connectionTelemetryState(connection)) }
    DisposableEffect(route.page, ui.navigation.homeTab, foreground) {
        val started = platformMonotonicNanos()
        val page = iosTelemetryPage(route, ui.navigation.homeTab)
        onDispose {
            if (foreground) data.telemetry.recordPageDwell(page,
                ((platformMonotonicNanos() - started) / 1_000_000).coerceAtLeast(0),
                if (IosApplicationRuntime.foreground.value) ClientPageExitReason.NAVIGATION else ClientPageExitReason.BACKGROUND)
        }
    }
    GroupInviteEntryEffect(data.discovery, foreground && connection == ConnectionState.AUTHENTICATED) { invite -> ui.navigation.open(IosRoute(IosPage.JOIN_BY_INVITE, invite)) }
    CompositionLocalProvider(
        LocalIosBackDispatcher provides ui.navigation.backDispatcher,
        LocalIdentityImageMediaConfig provides IdentityImageMediaConfig { attachment, modifier -> IosAttachmentImage(attachment, ui.media, modifier) },
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().pointerInput(route, edgeWidth, backDistance) {
            var edge = false
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { point -> edge = point.x < edgeWidth; distance = 0f },
                onHorizontalDrag = { change, amount -> if (edge) { distance += amount; change.consume() } },
                onDragEnd = { if (edge && distance > backDistance) ui.navigation.back() },
            )
        }) {
            Column(Modifier.fillMaxSize()) {
                if (connection != ConnectionState.AUTHENTICATED) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(if (connection == ConnectionState.DISCONNECTED) "当前离线，已保存的内容仍可使用" else "正在连接并同步…",
                        modifier = Modifier.fillMaxWidth())
                }
                ProtocolUpgradeBanner(compatibility)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (route.page) {
                        IosPage.HOME -> IosHomeScreen(ui, onLogout)
                        IosPage.CHAT -> IosChatScreen(route, ui)
                        IosPage.LOCAL_STORAGE -> IosStorageScreen(ui)
                        else -> IosFeatureScreen(route, ui)
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).imePadding())
        }
    }
}

private fun iosTelemetryPage(route: IosRoute, tab: MainTab): ClientUiPage = when (route.page) {
    IosPage.HOME -> when (tab) {
        MainTab.CONVERSATIONS -> ClientUiPage.CONVERSATIONS; MainTab.CONTACTS -> ClientUiPage.CONTACTS
        MainTab.DOCUMENTS -> ClientUiPage.DOCUMENTS; MainTab.TASKS -> ClientUiPage.TASKS; MainTab.SETTINGS -> ClientUiPage.SETTINGS
    }
    IosPage.CHAT -> ClientUiPage.CHAT
    IosPage.CHAT_TOOLS -> ClientUiPage.CHAT_TOOLS
    IosPage.CHAT_HISTORY_SEARCH -> ClientUiPage.CHAT_HISTORY_SEARCH
    IosPage.CHAT_HISTORY_BROWSER -> ClientUiPage.CHAT_HISTORY_BROWSER
    IosPage.SEARCH -> ClientUiPage.SEARCH_MESSAGES
    IosPage.SEARCH_USERS -> ClientUiPage.SEARCH_USERS
    IosPage.CREATE_GROUP -> ClientUiPage.CREATE_GROUP
    IosPage.FRIEND_APPLIES -> ClientUiPage.FRIEND_APPLIES
    IosPage.USER_PROFILE -> ClientUiPage.USER_PROFILE
    IosPage.EDIT_PROFILE -> ClientUiPage.EDIT_PROFILE
    IosPage.CHANGE_PASSWORD -> ClientUiPage.CHANGE_PASSWORD
    IosPage.DEVICES -> ClientUiPage.DEVICES
    IosPage.BLACKLIST -> ClientUiPage.BLACKLIST
    IosPage.GROUP_DETAIL -> ClientUiPage.GROUP_DETAIL
    IosPage.GROUP_FILES -> ClientUiPage.GROUP_FILES
    IosPage.GROUP_BOTS -> ClientUiPage.GROUP_BOTS
    IosPage.INVITE_MEMBERS -> ClientUiPage.INVITE_MEMBERS
    IosPage.INVITE_LINKS -> ClientUiPage.INVITE_LINKS
    IosPage.JOIN_BY_INVITE -> ClientUiPage.JOIN_BY_INVITE
    IosPage.FORWARD -> ClientUiPage.FORWARD
    IosPage.LOCAL_STORAGE -> ClientUiPage.SETTINGS
}
