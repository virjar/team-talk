@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.feature.document.DocumentDraftStore
import com.virjar.tk.shared.client.*
import com.virjar.tk.shared.repository.createChatAssetSpool
import kotlinx.coroutines.*
import platform.Foundation.NSThread
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

internal class IosSessionUi(
    val session: ClientSession,
    private val draftPersistence: IosDocumentDraftPersistence,
    onAuthExpired: () -> Unit,
    onHttpAuthExpired: (String) -> Unit,
) {
    val data = AppDataState(session, documentDrafts = DocumentDraftStore(draftPersistence),
        onAuthExpired = onAuthExpired, onHttpAuthExpired = onHttpAuthExpired)
    val navigation = IosNavigator()
    val media = IosMediaResources(data)
    val native = IosNativeMedia(media)
    val files = IosFileDownloadController(media, native, data.telemetry)
    val recorder = IosVoiceRecorder(media)
    val voice = IosVoicePlayback(media, recorder::close)
    val imports = IosEmbeddedAssetImportGateway(media, IosFileTransfer(media), native,
        publishOnUi = { action -> media.scope.launch { if (data.acceptsRendering) action() } },
        durableImports = data.chat.chatAssetImports {
            createChatAssetSpool(platformDataDir(), AccountDataOwner(session.deploymentIdentity.fingerprint, session.datasetId, session.ownerUid))
        })
    private var closed = false
    private val audioInterruption = NSNotificationCenter.defaultCenter.addObserverForName(
        AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue,
    ) {
        if (!closed) { recorder.close(); voice.close() }
    }
    init { data.activateHttpAuthExpiredDelivery() }

    fun captureDrafts() {
        val actions = listOf<() -> Unit>(
            { data.chat.draftLifecycle.captureLatest() },
            { check(data.documents.captureDrafts()) { "Document draft capture failed" } },
        )
        var failure: Throwable? = null
        actions.forEach { capture ->
            try { capture() }
            catch (next: Throwable) { if (failure == null) failure = next else if (failure !== next) failure?.addSuppressed(next) }
        }
        failure?.let { throw it }
    }
    fun close(reason: SessionEndReason) = onIosMain {
        if (closed) return@onIosMain
        closed = true
        NSNotificationCenter.defaultCenter.removeObserver(audioInterruption)
        // 先退役平台侧资源借用方，再允许 ClientSession 关闭本地缓存与 HTTP 身份。
        val failures = mutableListOf<Throwable>()
        fun release(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
        release { recorder.close() }; release { voice.close() }; release { native.close() }
        release { imports.close() }; release { files.close() }; release { media.close() }
        release { data.destroy(clearComposerContexts = true, clearDocumentDrafts = reason == SessionEndReason.USER_LOGOUT) }
        // 先捕获未保存草稿，再退役本会话草稿存储；若后台已有恢复读取正在 Default 线程进行，须等待其准入完成后再做账号清理。
        release { data.documentDrafts.retire(data.documentDraftOwnerKey) }
        // 不可变快照由进程级写入器独立持有，不随 SDK/缓存销毁；上方显式登出的删除是同步落盘的，保留草稿则无需在主线程等待 fsync。
        release { IosApplicationRuntime.observeDraftFlush("IosSessionDraft") }
        failures.firstOrNull()?.let { primary -> failures.drop(1).forEach(primary::addSuppressed); throw primary }
    }
}

internal fun onIosMain(action: () -> Unit) {
    if (NSThread.isMainThread) action() else dispatch_sync(dispatch_get_main_queue(), action)
}
