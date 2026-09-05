package com.virjar.tk.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientPageExitReason
import com.virjar.tk.app.telemetry.ClientSystemEvent
import com.virjar.tk.app.telemetry.ClientSystemState
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.PageDwellTracker

/** 独立于导航图，拥有页面驻留与进程生命周期遥测。 */
@Composable
internal fun AndroidTelemetryLifecycle(
    telemetry: ClientUiTelemetrySink,
    connectionState: ConnectionState,
    currentPage: ClientUiPage,
    acceptsRendering: () -> Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPage by rememberUpdatedState(currentPage)
    val pageDwell = remember(telemetry) {
        PageDwellTracker(System::currentTimeMillis, telemetry::recordPageDwell)
    }
    LaunchedEffect(currentPage, lifecycleOwner) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pageDwell.enter(currentPage)
            telemetry.recordAction(
                currentPage,
                ClientUiAction.OPEN_PAGE,
                ClientActionOutcome.SUCCEEDED,
            )
        }
    }
    LaunchedEffect(connectionState) {
        telemetry.recordSystem(
            ClientSystemEvent.CONNECTION_STATE,
            androidConnectionTelemetryState(connectionState),
        )
    }
    DisposableEffect(lifecycleOwner, pageDwell) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    telemetry.recordSystem(
                        ClientSystemEvent.APP_FOREGROUND,
                        ClientSystemState.FOREGROUND,
                    )
                    pageDwell.enter(latestPage)
                }
                Lifecycle.Event.ON_STOP -> {
                    pageDwell.pause()
                    telemetry.recordSystem(
                        ClientSystemEvent.APP_BACKGROUND,
                        ClientSystemState.BACKGROUND,
                    )
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            telemetry.recordSystem(
                ClientSystemEvent.APP_FOREGROUND,
                ClientSystemState.FOREGROUND,
            )
            pageDwell.enter(latestPage)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pageDwell.finish(androidPageDwellDisposalReason(acceptsRendering()))
        }
    }
}

/** 精确的路由模式映射；参数和恢复出来的动态路由绝不会进入遥测。 */
internal fun androidTelemetryPage(route: String?): ClientUiPage = when (route) {
    Routes.CHAT -> ClientUiPage.CHAT
    Routes.SEARCH_MESSAGES -> ClientUiPage.SEARCH_MESSAGES
    Routes.SEARCH_USERS -> ClientUiPage.SEARCH_USERS
    Routes.CREATE_GROUP -> ClientUiPage.CREATE_GROUP
    Routes.FRIEND_APPLIES -> ClientUiPage.FRIEND_APPLIES
    Routes.USER_PROFILE -> ClientUiPage.USER_PROFILE
    Routes.EDIT_PROFILE -> ClientUiPage.EDIT_PROFILE
    Routes.CHANGE_PASSWORD -> ClientUiPage.CHANGE_PASSWORD
    Routes.DEVICES -> ClientUiPage.DEVICES
    Routes.BLACKLIST -> ClientUiPage.BLACKLIST
    Routes.GROUP_DETAIL -> ClientUiPage.GROUP_DETAIL
    Routes.GROUP_FILES -> ClientUiPage.GROUP_FILES
    Routes.GROUP_BOTS -> ClientUiPage.GROUP_BOTS
    Routes.INVITE_MEMBERS -> ClientUiPage.INVITE_MEMBERS
    Routes.INVITE_LINKS -> ClientUiPage.INVITE_LINKS
    Routes.FORWARD -> ClientUiPage.FORWARD
    Routes.TEXT_ATTACHMENT_PREVIEW -> ClientUiPage.TEXT_ATTACHMENT_PREVIEW
    else -> ClientUiPage.CONVERSATIONS
}

internal fun androidConnectionTelemetryState(state: ConnectionState): ClientSystemState = when (state) {
    ConnectionState.DISCONNECTED -> ClientSystemState.DISCONNECTED
    ConnectionState.CONNECTING -> ClientSystemState.CONNECTING
    ConnectionState.CONNECTED -> ClientSystemState.CONNECTED
    ConnectionState.SYNCHRONIZING -> ClientSystemState.SYNCHRONIZING
    ConnectionState.AUTHENTICATED -> ClientSystemState.AUTHENTICATED
    ConnectionState.AUTH_FAILED -> ClientSystemState.AUTHENTICATION_FAILED
}

internal fun androidHomeTabTelemetryPage(tab: MainTab): ClientUiPage = when (tab) {
    MainTab.CONVERSATIONS -> ClientUiPage.CONVERSATIONS
    MainTab.CONTACTS -> ClientUiPage.CONTACTS
    MainTab.DOCUMENTS -> ClientUiPage.DOCUMENTS
    MainTab.SETTINGS -> ClientUiPage.SETTINGS
}

/** Activity 重建会保留 AppDataState；只有退役的渲染所有者才表示会话结束。 */
internal fun androidPageDwellDisposalReason(acceptsRendering: Boolean): ClientPageExitReason =
    if (acceptsRendering) ClientPageExitReason.BACKGROUND else ClientPageExitReason.SESSION_END
