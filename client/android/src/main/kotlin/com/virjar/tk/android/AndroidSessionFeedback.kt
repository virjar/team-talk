package com.virjar.tk.android

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.UserFeedbackCode
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 会话反馈在 Activity 重建后仍然保留，直到替换后的 Snackbar 宿主完成展示。 */
@Composable
internal fun rememberAndroidSessionFeedback(
    dataState: AppDataState,
    currentPage: ClientUiPage,
): SnackbarHostState {
    val snackbarHostState = remember(dataState) { SnackbarHostState() }
    val latestPage = rememberUpdatedState(currentPage)
    val presentationMutex = remember(dataState) { Mutex() }
    val errorHost = remember(dataState) { Any() }
    LaunchedEffect(dataState) {
        snapshotFlow { dataState.errorSignal }
            .filterNotNull()
            .collect {
                presentationMutex.withLock {
                    val lease = dataState.acquireError(errorHost) ?: return@withLock
                    try {
                        val message = if (dataState.markErrorDisplayed(lease)) {
                            dataState.feedbackReporter.displayed(
                                feedbackCode = UserFeedbackCode.forDisplayedMessage(lease.value),
                                page = latestPage.value,
                                action = ClientUiAction.SHOW_FEEDBACK,
                                origin = FeedbackOrigin.SNACKBAR,
                            )
                        } else {
                            lease.value
                        }
                        snackbarHostState.showSnackbar(message)
                        dataState.completeError(lease)
                    } finally {
                        // Activity 重建会在 showSnackbar 挂起期间取消此效果。
                        // 释放租约让同一个事件仍然可供替换后的宿主使用。
                        dataState.releaseError(lease)
                    }
                }
            }
    }
    val noticeHost = remember(dataState) { Any() }
    LaunchedEffect(dataState) {
        snapshotFlow { dataState.noticeSignal }
            .filterNotNull()
            .collect {
                presentationMutex.withLock {
                    val lease = dataState.acquireNotice(noticeHost) ?: return@withLock
                    try {
                        val message = if (dataState.markNoticeDisplayed(lease)) {
                            dataState.feedbackReporter.displayed(lease.value)
                        } else {
                            lease.value.publicMessage
                        }
                        snackbarHostState.showSnackbar(message)
                        dataState.completeNotice(lease)
                    } finally {
                        dataState.releaseNotice(lease)
                    }
                }
            }
    }
    return snackbarHostState
}
