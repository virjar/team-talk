package com.virjar.tk.android

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportBinding
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportBindingRouter
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportPlacement
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportRegistration
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportRetryStore
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSource
import com.virjar.tk.app.ui.bridge.EmbeddedAssetLocalSelection
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Activity 结果启动器归 Compose 所有；这个稳定持有者让网关可以选择其一。 */
internal class AndroidEmbeddedAssetSelector {
    var pickImage: () -> Unit = {}
    var pickFile: () -> Unit = {}

    fun select(presentation: EmbeddedAssetPresentation) = when (presentation) {
        EmbeddedAssetPresentation.IMAGE -> pickImage()
        EmbeddedAssetPresentation.FILE -> pickFile()
    }
}

internal suspend fun resolveAndroidEmbeddedAssetSelection(
    context: Context,
    uri: Uri,
    presentation: EmbeddedAssetPresentation?,
    source: EmbeddedAssetImportSource,
): EmbeddedAssetLocalSelection = withContext(Dispatchers.IO) {
    val contentType = runCatching { MediaHelper.getMimeType(context, uri) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "application/octet-stream"
    val resolvedPresentation = presentation ?: if (contentType.startsWith("image/", ignoreCase = true)) {
        EmbeddedAssetPresentation.IMAGE
    } else {
        EmbeddedAssetPresentation.FILE
    }
    EmbeddedAssetLocalSelection(
        localReference = uri.toString(),
        displayName = runCatching { MediaHelper.getFileName(context, uri) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: if (resolvedPresentation == EmbeddedAssetPresentation.IMAGE) "图片" else "附件",
        contentType = contentType,
        size = runCatching { MediaHelper.getFileSize(context, uri) }.getOrDefault(0L),
        presentation = resolvedPresentation,
        source = source,
    )
}

internal data class AndroidEmbeddedAssetPickerToken(
    val ownerKey: String,
    val presentation: EmbeddedAssetPresentation,
) {
    init {
        require(ownerKey.isNotBlank()) { "Picker owner must not be blank" }
    }

    fun belongsTo(ownerKey: String): Boolean = this.ownerKey == ownerKey
}

/** 可保存的一次性 owner 令牌；它不包含任何 URI、凭证或所选文件元数据。 */
internal class AndroidEmbeddedAssetPickerContinuation(
    initialToken: AndroidEmbeddedAssetPickerToken? = null,
) {
    private val lock = Any()
    private var token: AndroidEmbeddedAssetPickerToken? = initialToken

    fun begin(ownerKey: String, presentation: EmbeddedAssetPresentation): AndroidEmbeddedAssetPickerToken? =
        synchronized(lock) {
            if (token != null) return@synchronized null
            AndroidEmbeddedAssetPickerToken(ownerKey, presentation).also { token = it }
        }

    fun take(presentation: EmbeddedAssetPresentation): AndroidEmbeddedAssetPickerToken? = synchronized(lock) {
        token?.takeIf { it.presentation == presentation }?.also { token = null }
    }

    fun clear(expected: AndroidEmbeddedAssetPickerToken) {
        synchronized(lock) {
            if (token == expected) token = null
        }
    }

    fun snapshot(): AndroidEmbeddedAssetPickerToken? = synchronized(lock) { token }

    companion object {
        val Saver = Saver<AndroidEmbeddedAssetPickerContinuation, ArrayList<String>>(
            save = { continuation ->
                continuation.snapshot()?.let { token ->
                    arrayListOf(token.ownerKey, token.presentation.name)
                } ?: arrayListOf()
            },
            restore = { saved ->
                val restored = if (saved.size == 2) {
                    runCatching {
                        AndroidEmbeddedAssetPickerToken(
                            ownerKey = saved[0],
                            presentation = EmbeddedAssetPresentation.valueOf(saved[1]),
                        )
                    }.getOrNull()
                } else null
                AndroidEmbeddedAssetPickerContinuation(restored)
            },
        )
    }
}

internal data class AndroidEmbeddedAssetReturnedPickerSelection(
    val token: AndroidEmbeddedAssetPickerToken,
    val localReference: String,
) {
    init {
        require(localReference.isNotBlank()) { "Returned picker URI must not be blank" }
    }
}

/**
 * 弥合短暂的组合间隙：ActivityResult 在恢复后的编辑器重新绑定之前就已送达。
 * 只有原始 owner 才能消费返回的那一个 URI。
 */
internal class AndroidEmbeddedAssetReturnedPickerSelections {
    private val lock = Any()
    private var pending: AndroidEmbeddedAssetReturnedPickerSelection? = null

    fun offer(token: AndroidEmbeddedAssetPickerToken, localReference: String): Boolean =
        synchronized(lock) {
            if (pending != null) return@synchronized false
            pending = AndroidEmbeddedAssetReturnedPickerSelection(token, localReference)
            true
        }

    fun takeFor(ownerKey: String): AndroidEmbeddedAssetReturnedPickerSelection? = synchronized(lock) {
        pending?.takeIf { it.token.belongsTo(ownerKey) }?.also { pending = null }
    }

    fun clear(expected: AndroidEmbeddedAssetPickerToken? = null) {
        synchronized(lock) {
            if (expected == null || pending?.token == expected) pending = null
        }
    }
}

/** Android 选择器/剪贴板适配器，共享经过认证的 MediaHelper 上传路径。 */
internal class AndroidEmbeddedAssetImportGateway(
    private val context: Context,
    private val mediaSession: AndroidMediaSession,
    private val selector: AndroidEmbeddedAssetSelector,
    private val pickerContinuation: AndroidEmbeddedAssetPickerContinuation,
    private val launchAdmittedAction: (suspend () -> Unit) -> Boolean,
    private val launchCancellableAdmittedAction: (suspend () -> Unit) -> Job?,
    private val deliverIfOpen: (() -> Unit) -> Boolean,
) : EmbeddedAssetImportGateway, Closeable {
    private val bindings = EmbeddedAssetImportBindingRouter()
    private val pickerLock = Any()
    private var pendingPicker: PendingPicker? = null
    private val retryStore = EmbeddedAssetImportRetryStore<PreparedMedia>(
        releaseSource = PreparedMedia::delete,
    )
    private val returnedPickerSelections = AndroidEmbeddedAssetReturnedPickerSelections()

    override fun bind(
        ownerKey: String,
        sink: EmbeddedAssetImportEventSink,
        acceptNewImports: Boolean,
    ): EmbeddedAssetImportRegistration {
        val registration = bindings.bind(ownerKey, sink, acceptNewImports)
        retryStore.replay(ownerKey).forEach { job ->
            sink.publish(EmbeddedAssetImportEvent.StateChanged(job = job, placement = null))
        }
        drainReturnedPickerSelection()
        return registration
    }

    override fun select(presentation: EmbeddedAssetPresentation) {
        val binding = bindings.captureForImport() ?: return
        val token = pickerContinuation.begin(binding.ownerKey, presentation) ?: return
        val picker = PendingPicker(
            binding = binding,
            token = token,
        )
        synchronized(pickerLock) { pendingPicker = picker }
        try {
            selector.select(presentation)
        } catch (failure: Exception) {
            synchronized(pickerLock) {
                if (pendingPicker === picker) pendingPicker = null
            }
            pickerContinuation.clear(token)
            throw failure
        }
    }

    override fun import(selection: EmbeddedAssetLocalSelection) {
        val binding = bindings.captureForImport() ?: return
        beginImport(binding, selection)
    }

    override fun cancel(jobId: String): Boolean {
        val cancelled = retryStore.cancel(jobId) ?: return false
        publishTerminal(cancelled.binding, EmbeddedAssetImportEvent.StateChanged(cancelled.job))
        return true
    }

    override fun retry(jobId: String): Boolean {
        val attempt = retryStore.retry(jobId) ?: return when (retryStore.state(jobId)) {
            PendingAssetJobState.PREPARING,
            PendingAssetJobState.UPLOADING,
            -> true
            else -> false
        }
        publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(attempt.job))
        launchAttempt(attempt)
        return true
    }

    /** 完成成功与取消两种结局，且恰好消费一次已保存的 owner 令牌。 */
    fun completePicker(presentation: EmbeddedAssetPresentation, uri: Uri?) {
        val livePicker = synchronized(pickerLock) {
            pendingPicker?.takeIf { it.token.presentation == presentation }?.also {
                pendingPicker = null
            }
        }
        val token = pickerContinuation.snapshot()?.takeIf { it.presentation == presentation } ?: return
        if (uri == null) {
            returnedPickerSelections.clear(token)
            pickerContinuation.clear(token)
            return
        }
        val originalBinding = livePicker?.takeIf { it.token == token }?.binding
        if (originalBinding != null) {
            if (pickerContinuation.take(presentation) != token) return
            scheduleUriImport(
                binding = originalBinding,
                uri = uri,
                presentation = presentation,
                source = EmbeddedAssetImportSource.ANDROID_PICKER,
            )
            return
        }
        if (returnedPickerSelections.offer(token, uri.toString())) {
            // ActivityResultRegistry 可能在恢复后的编辑器尚未重新绑定时就调用其回调。
            // bind() 也会执行排空，因此这场竞态的任何一方都能完成投递。
            drainReturnedPickerSelection()
        }
    }

    private fun drainReturnedPickerSelection() {
        val binding = bindings.capture() ?: return
        val returned = returnedPickerSelections.takeFor(binding.ownerKey) ?: return
        if (pickerContinuation.take(returned.token.presentation) != returned.token) return
        scheduleUriImport(
            binding = binding,
            uri = Uri.parse(returned.localReference),
            presentation = returned.token.presentation,
            source = EmbeddedAssetImportSource.ANDROID_PICKER,
        )
    }

    /** 在从 Main 线程读取任何提供者元数据之前，先捕获当前编辑器。 */
    fun importUri(
        uri: Uri,
        presentation: EmbeddedAssetPresentation?,
        source: EmbeddedAssetImportSource,
    ): Boolean {
        val binding = bindings.captureForImport() ?: return false
        return scheduleUriImport(binding, uri, presentation, source)
    }

    private fun scheduleUriImport(
        binding: EmbeddedAssetImportBinding,
        uri: Uri,
        presentation: EmbeddedAssetPresentation?,
        source: EmbeddedAssetImportSource,
    ): Boolean = launchAdmittedAction {
        val selection = resolveAndroidEmbeddedAssetSelection(
            context = context,
            uri = uri,
            presentation = presentation,
            source = source,
        )
        beginImport(binding, selection)
    }

    private fun beginImport(
        binding: EmbeddedAssetImportBinding,
        selection: EmbeddedAssetLocalSelection,
    ) {
        val assetId = UUID.randomUUID().toString()
        val job = PendingAssetJob(UUID.randomUUID().toString(), assetId)
        val placement = EmbeddedAssetImportPlacement(selection.displayName, selection.presentation)
        val attempt = retryStore.create(
            binding = binding,
            selection = selection,
            placement = placement,
            identity = AttachmentUploadIdentity(UUID.randomUUID().toString(), System.currentTimeMillis()),
            job = job,
        ) ?: return
        // 在准备/上传开始之前先发布位置信息。平台手势已经冻结了 [binding]，
        // 因此元数据解析或路由变化都无法重定向后续的帧。
        if (!publish(binding, EmbeddedAssetImportEvent.StateChanged(job, placement))) {
            retryStore.cancel(job.jobId)
            return
        }
        launchAttempt(attempt)
    }

    /**
     * 首次准备会把提供者的字节冻结成一个缓存文件。失败的 HTTP 尝试会保留该确切的快照和身份；
     * 重试绝不会重新读取可变的 content URI。
     */
    private fun launchAttempt(attempt: EmbeddedAssetImportRetryStore.Attempt<PreparedMedia>) {
        val startGate = CompletableDeferred<Unit>()
        val uploadTask = launchCancellableAdmittedAction {
            // Android 的会话启动器以 UNDISPATCHED 方式启动。先挂起工作，直到返回的 Job 挂接到
            // 存储上，这样在读取字节之前 cancel/close 总能找到任务所有者。
            startGate.await()
            try {
                var job = attempt.job
                if (job.state == PendingAssetJobState.LOCAL) {
                    job = retryStore.transition(attempt, PendingAssetJob::beginPreparing)
                        ?: return@launchCancellableAdmittedAction
                    publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(job))
                }
                var prepared = attempt.source ?: retryStore.source(attempt)
                if (prepared == null) {
                    val candidate = MediaHelper.prepareSelectedMedia(
                        context = context,
                        uri = Uri.parse(attempt.selection.localReference),
                        mediaSession = mediaSession,
                    )
                    if (!retryStore.attachSource(attempt, candidate)) {
                        candidate.delete()
                        return@launchCancellableAdmittedAction
                    }
                    prepared = candidate
                }
                currentCoroutineContext().ensureActive()
                job = retryStore.transition(attempt, PendingAssetJob::beginUploading)
                    ?: return@launchCancellableAdmittedAction
                publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(job))
                val uploaded = MediaHelper.uploadWithMeta(
                    file = prepared.file,
                    fileName = prepared.fileName,
                    contentType = prepared.contentType,
                    mediaSession = mediaSession,
                    identity = attempt.identity,
                )
                currentCoroutineContext().ensureActive()
                job = retryStore.completeReady(attempt) ?: return@launchCancellableAdmittedAction
                publishTerminal(
                    attempt.binding,
                    EmbeddedAssetImportEvent.Ready(
                        job = job,
                        asset = EmbeddedAsset(
                            assetId = attempt.assetId,
                            attachment = uploaded.file,
                            thumbnail = uploaded.thumbnail,
                            width = uploaded.width,
                            height = uploaded.height,
                        ),
                        placement = attempt.placement,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = failure.message?.takeIf(String::isNotBlank) ?: "上传失败"
                retryStore.fail(attempt, reason)?.let { failed ->
                    publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(failed))
                }
            }
        }
        if (uploadTask == null) {
            retryStore.cancel(attempt.jobId)?.let { cancelled ->
                publishTerminal(cancelled.binding, EmbeddedAssetImportEvent.StateChanged(cancelled.job))
            }
        } else if (retryStore.attach(attempt, uploadTask)) {
            startGate.complete(Unit)
        } else {
            uploadTask.cancel()
        }
    }

    private fun publishCurrent(
        attempt: EmbeddedAssetImportRetryStore.Attempt<PreparedMedia>,
        event: EmbeddedAssetImportEvent.StateChanged,
    ) {
        if (retryStore.isCurrent(attempt, event.job)) publish(attempt.binding, event)
    }

    private fun publishTerminal(
        binding: EmbeddedAssetImportBinding,
        event: EmbeddedAssetImportEvent,
    ) {
        publish(binding, event)
    }

    private fun publish(
        binding: EmbeddedAssetImportBinding,
        event: EmbeddedAssetImportEvent,
    ): Boolean {
        var delivered = false
        val admitted = deliverIfOpen {
            delivered = bindings.publish(binding, event)
        }
        return admitted && delivered
    }

    override fun close() {
        synchronized(pickerLock) { pendingPicker = null }
        returnedPickerSelections.clear()
        // 可保存的延续对象有意在 Activity 重建后存活。它的组合所有者会随账户/数据集切换而更换，
        // 因此 close 绝不能擦除一个正在进行中的结果。
        bindings.close()
        retryStore.close()
    }

    private data class PendingPicker(
        val binding: EmbeddedAssetImportBinding,
        val token: AndroidEmbeddedAssetPickerToken,
    )
}

/** 对纯文本剪贴板内容返回 false，让编辑器可以执行其常规粘贴。 */
internal fun importAndroidClipboardAsset(
    context: Context,
    gateway: AndroidEmbeddedAssetImportGateway,
): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return@runCatching false
    val clip = clipboard.primaryClip ?: return@runCatching false
    val item = clip.getItemAt(0)
    val uri = item.uri ?: item.intent?.data ?: return@runCatching false
    gateway.importUri(
        uri = uri,
        presentation = null,
        source = EmbeddedAssetImportSource.ANDROID_CLIPBOARD,
    )
}.getOrDefault(false)
