package com.virjar.tk.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.navigation.feature.document.DocumentDraftStore
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.ui.screen.ChatComposerContextStore
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 在 Activity 重建期间保留会话持有的编辑器上下文和未保存的文档正文。
 * 普通配置变化由 Activity 就地处理；发生显式重建时，此持有者还能保护大的延续状态。
 * 不同的已登录 uid 或服务器数据集总是获得全新的存储，因此绝不会继承过期的草稿状态。
 */
internal class AndroidAppDataStateHolder(application: Application) : AndroidViewModel(application) {
    val notificationNavigation = AndroidNotificationNavigation()
    private val notificationForeground = MutableStateFlow(true)
    private val documentDraftPersistence =
        (application as TeamTalkApp).documentDraftPersistence
    private var composerContexts = ChatComposerContextStore()
    private var documentDrafts = newDocumentDraftStore()
    private var dataState: AppDataState? = null
    private var authenticatedResources: AndroidAuthenticatedResourceOwner? = null
    private var continuationOwnerKey: DocumentDraftOwnerKey? = null
    private val sessionOwner = AndroidSessionOwnerGate<ClientSession>()

    fun forSession(
        session: ClientSession,
        onAuthExpired: () -> Unit,
        onHttpAuthExpired: (rejectedAccessToken: String) -> Unit,
    ): AndroidAuthenticatedUiSession {
        val uiSession = sessionOwner.replaceOwner(session) { previousSession ->
            val currentState = dataState
            val currentResources = authenticatedResources
            if (previousSession === session && currentState != null && currentResources != null) {
                return@replaceOwner AndroidAuthenticatedUiSession(currentState, currentResources)
            }
            val previous = currentState
            val previousTelemetry = previous?.telemetry ?: NoopClientUiTelemetrySink
            reportAndroidRetirementFailures(
                boundary = "session replacement",
                observedFailures = closeAuthenticatedResources(),
                telemetry = previousTelemetry,
            )
            val nextOwnerKey = DocumentDraftOwnerKey(
                deploymentFingerprint = session.deploymentIdentity.fingerprint,
                datasetId = session.datasetId,
                uid = session.ownerUid,
            )
            val previousOwnerKey = previous?.documentDraftOwnerKey ?: continuationOwnerKey
            val sameOwner = previousOwnerKey == nextOwnerKey
            if (sameOwner) previous?.documents?.captureDrafts()
            previous?.destroy(
                clearComposerContexts = !sameOwner,
                clearDocumentDrafts = !sameOwner,
            )
            if (sameOwner) {
                scheduleDocumentDraftFlush(
                    boundary = "same-owner session replacement",
                    telemetry = previousTelemetry,
                )
            }
            // AuthController 拥有传输层。同一个 ImClient 启动较新的登录后，被保留的持有者仍可能
            // 引用一个已关闭的会话；这里只释放旧会话资源，绝不由持有者请求传输层断开连接。
            previousSession?.takeIf { it !== session }?.close(
                reason = SessionEndReason.PROCESS_REPLACED,
                disconnectTransport = false,
            )
            if (!sameOwner) {
                composerContexts = ChatComposerContextStore()
                documentDrafts = newDocumentDraftStore()
            }
            continuationOwnerKey = null
            AppDataState(
                session = session,
                chatComposerContexts = composerContexts,
                documentDrafts = documentDrafts,
                onAuthExpired = onAuthExpired,
                onHttpAuthExpired = onHttpAuthExpired,
            ).let { state ->
                val resources = AndroidAuthenticatedResourceOwner()
                dataState = state
                authenticatedResources = resources
                val cache = session.localCache
                resources.acquire {
                    AndroidMessageNotifications(
                        context = getApplication<Application>().applicationContext,
                        deploymentFingerprint = session.deploymentIdentity.fingerprint,
                        datasetId = session.datasetId,
                        uid = session.ownerUid,
                        // 缓存的首帧已经加载完成；ViewModel 初始 emptyList 尚不代表这个事实。
                        conversations = UiLocalDataBoundary().projection(cache::observeConversations),
                        connectionState = session.connectionState,
                        foreground = notificationForeground,
                        navigation = notificationNavigation,
                    )
                }
                AndroidAuthenticatedUiSession(state, resources)
            }
        }
        // replaceOwner 只会在其状态转换返回之后发布确切的 ClientSession。早期的 HTTP 401
        // 会一直排队到此时，因此 runIfSessionOwner 无法拒绝并丢失它们。
        uiSession.dataState.activateHttpAuthExpiredDelivery()
        return uiSession
    }

    /** AuthController 会在匹配的会话有机会关闭 LocalCache 之前同步调用此方法。 */
    fun beforeSessionRetirement(session: ClientSession, reason: SessionEndReason) {
        sessionOwner.retireIfOwner(session) {
            val failures = mutableListOf<Pair<String, Throwable>>()
            fun release(owner: String, block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += owner to failure
                }
            }

            val retiringState = dataState
            val retiringTelemetry = retiringState?.telemetry ?: NoopClientUiTelemetrySink
            failures += closeAuthenticatedResources()
            dataState = null
            when (reason.androidUiRetirementPolicy()) {
                AndroidUiRetirementPolicy.DISCARD_DRAFTS -> {
                    continuationOwnerKey = null
                    release("AppDataState") {
                        retiringState?.destroy(clearComposerContexts = true, clearDocumentDrafts = true)
                    }
                    release("document draft flush") {
                        scheduleDocumentDraftFlush(
                            boundary = "discard-drafts retirement",
                            telemetry = retiringTelemetry,
                        )
                    }
                    release("composer store reset") { composerContexts = ChatComposerContextStore() }
                    release("document store reset") { documentDrafts = newDocumentDraftStore() }
                }

                AndroidUiRetirementPolicy.PRESERVE_DURABLE_DRAFTS -> {
                    continuationOwnerKey = null
                    release("document draft capture") { retiringState?.documents?.captureDrafts() }
                    release("AppDataState") {
                        retiringState?.destroy(clearComposerContexts = true, clearDocumentDrafts = false)
                    }
                    release("document draft flush") {
                        scheduleDocumentDraftFlush(
                            boundary = "preserve-drafts retirement",
                            telemetry = retiringTelemetry,
                        )
                    }
                    release("composer store reset") { composerContexts = ChatComposerContextStore() }
                    release("document store reset") { documentDrafts = newDocumentDraftStore() }
                }

                AndroidUiRetirementPolicy.PRESERVE_SAME_USER_CONTINUATION -> {
                    continuationOwnerKey = DocumentDraftOwnerKey(
                        deploymentFingerprint = session.deploymentIdentity.fingerprint,
                        datasetId = session.datasetId,
                        uid = session.ownerUid,
                    )
                    release("document draft capture") { retiringState?.documents?.captureDrafts() }
                    release("AppDataState") {
                        retiringState?.destroy(clearComposerContexts = false, clearDocumentDrafts = false)
                    }
                    release("document draft flush") {
                        scheduleDocumentDraftFlush(
                            boundary = "same-user continuation",
                            telemetry = retiringTelemetry,
                        )
                    }
                }
            }
            reportAndroidRetirementFailures(
                boundary = "$reason retirement",
                observedFailures = failures,
                telemetry = retiringTelemetry,
            )
        }
    }

    fun captureAndScheduleDocumentDraftFlush() {
        sessionOwner.withOwner {
            val activeState = dataState
            val activeTelemetry = activeState?.telemetry ?: NoopClientUiTelemetrySink
            captureThenScheduleDocumentDraftFlush(
                captureDrafts = { activeState?.documents?.captureDrafts() ?: true },
                scheduleFlush = {
                    scheduleDocumentDraftFlush(
                        boundary = "Activity onStop",
                        telemetry = activeTelemetry,
                    )
                },
            )
        }
    }

    fun setNotificationForeground(foreground: Boolean) {
        notificationForeground.value = foreground
    }

    /** 只对确切保留的 ClientSession 放行 AuthController 的终结动作。 */
    fun runIfSessionOwner(session: ClientSession, action: () -> Unit): Boolean =
        sessionOwner.runIfOwner(session, action)

    override fun onCleared() {
        sessionOwner.retireCurrent { retainedSession ->
            val failures = mutableListOf<Pair<String, Throwable>>()
            fun release(owner: String, block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += owner to failure
                }
            }
            // 移除任务并不是显式的账户注销。保留按 uid 划分的 AtomicFile，
            // 让新进程可以恢复未保存的文档。
            val retiringState = dataState
            val retiringTelemetry = retiringState?.telemetry ?: NoopClientUiTelemetrySink
            failures += closeAuthenticatedResources()
            dataState = null
            release("document draft capture") { retiringState?.documents?.captureDrafts() }
            release("AppDataState") {
                retiringState?.destroy(clearComposerContexts = true, clearDocumentDrafts = false)
            }
            release("document draft flush") {
                scheduleDocumentDraftFlush(
                    boundary = "ViewModel clearing",
                    telemetry = retiringTelemetry,
                )
            }
            // 在 ClientSession.stop 执行遥测记录器的最终持久化之前，先记录已知的 UI/资源失败。
            // 即使诊断上报失败，会话关闭仍会执行；之后发现的关闭失败会保持现有的日志/抛出边界。
            closeAndroidRetainedSessionAfterFaultCapture(
                telemetry = retiringTelemetry,
                failures = failures,
                closeRetainedSession = {
                    retainedSession?.close(
                        reason = SessionEndReason.SHUTDOWN,
                        disconnectTransport = false,
                    )
                },
            )
            continuationOwnerKey = null
            finishAndroidRetirementFailures(
                boundary = "ViewModel clearing",
                failures = failures,
            )
        }
    }

    private fun newDocumentDraftStore() = DocumentDraftStore(documentDraftPersistence)

    /** 观察非阻塞屏障；持久化边界是完成而不是准入。 */
    private fun scheduleDocumentDraftFlush(
        boundary: String,
        telemetry: ClientUiTelemetrySink,
    ) {
        observeAndroidDocumentDraftFlush(
            completion = documentDraftPersistence.requestFlush(),
            telemetry = telemetry,
        ) { failure ->
            if (failure != null) {
                Log.e(
                    "DocumentDraft",
                    "Document draft durability barrier failed at $boundary",
                    failure,
                )
            } else {
                Log.e(
                    "DocumentDraft",
                    "Document draft durability barrier did not complete at $boundary",
                )
            }
        }
    }

    private fun closeAuthenticatedResources(): List<Pair<String, Throwable>> {
        val closing = authenticatedResources
        authenticatedResources = null
        return try {
            closing?.closeAll().orEmpty().mapIndexed { index, failure ->
                "authenticated resource ${index + 1}" to failure
            }
        } catch (failure: Throwable) {
            listOf("authenticated resources" to failure)
        }
    }
}

/** 完成回调保留所提供的 sink；它们绝不会再查询之后的持有者/会话。 */
internal fun observeAndroidDocumentDraftFlush(
    completion: CompletionStage<Boolean>,
    telemetry: ClientUiTelemetrySink,
    recordFailure: (Throwable?) -> Unit,
) {
    val lifecycleFault = AndroidPlatformLifecycleFaultReporter(telemetry)
    completion.whenComplete { succeeded, failure ->
        if (failure != null || succeeded != true) {
            lifecycleFault.report()
            recordFailure(failure)
        }
    }
}
