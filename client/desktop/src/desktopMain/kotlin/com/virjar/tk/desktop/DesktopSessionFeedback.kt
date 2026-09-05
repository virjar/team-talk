package com.virjar.tk.desktop

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.virjar.tk.app.navigation.UiEventLease
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.UserFeedbackNotice
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 主窗口反馈宿主；打开的任务窗口会接管所有权，因此提示保持可见。 */
@Composable
internal fun BoxScope.SessionFeedbackSnackbar(
    data: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val ownsFeedbackSurface = data.windowScreen == null
    val presentationMutex = remember(data) { Mutex() }
    val errorHost = remember(data) { Any() }
    LaunchedEffect(data, presentationGate, ownsFeedbackSurface) {
        if (!ownsFeedbackSurface) return@LaunchedEffect
        snapshotFlow { data.errorSignal }.collect { signal ->
            if (signal == null) return@collect
            presentationMutex.withLock {
                if (data.windowScreen != null) return@withLock
                var lease: UiEventLease<String>? = null
                if (!presentationGate.runIfOpen { lease = data.acquireError(errorHost) }) return@withLock
                val delivery = lease ?: return@withLock
                try {
                    val message = if (data.markErrorDisplayed(delivery)) {
                        data.feedbackReporter.displayed(
                            feedbackCode = UserFeedbackCode.forDisplayedMessage(delivery.value),
                            page = desktopTelemetryPage(data),
                            action = ClientUiAction.SHOW_FEEDBACK,
                            origin = FeedbackOrigin.SNACKBAR,
                        )
                    } else {
                        delivery.value
                    }
                    snackbarHostState.showSnackbar(message)
                    data.completeError(delivery)
                } finally {
                    data.releaseError(delivery)
                }
            }
        }
    }
    val noticeHost = remember(data) { Any() }
    LaunchedEffect(data, presentationGate, ownsFeedbackSurface) {
        if (!ownsFeedbackSurface) return@LaunchedEffect
        snapshotFlow { data.noticeSignal }.collect { signal ->
            if (signal == null) return@collect
            presentationMutex.withLock {
                if (data.windowScreen != null) return@withLock
                var lease: UiEventLease<UserFeedbackNotice>? = null
                if (!presentationGate.runIfOpen { lease = data.acquireNotice(noticeHost) }) return@withLock
                val delivery = lease ?: return@withLock
                try {
                    val message = if (data.markNoticeDisplayed(delivery)) {
                        data.feedbackReporter.displayed(delivery.value)
                    } else {
                        delivery.value.publicMessage
                    }
                    snackbarHostState.showSnackbar(message)
                    data.completeNotice(delivery)
                } finally {
                    data.releaseNotice(delivery)
                }
            }
        }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
}
