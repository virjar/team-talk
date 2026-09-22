@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virjar.tk.app.ui.component.*
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.platform.PlatformFile
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.ImageIO.*
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.UIKit.*

internal class IosFileDownloads(private val resources: IosMediaResources, private val native: IosNativeMedia) : FileDownloadController {
    override val states: SnapshotStateMap<String, FileDownloadState> = mutableStateMapOf()
    override val automaticDownloadLedger = AutomaticFileDownloadLedger()
    private val scope = resources.childScope("file-download")
    override fun ensure(attachment: Attachment) {
        if (states.containsKey(attachment.path) || !resources.canDeliverUiResult()) return
        states[attachment.path] = FileDownloadState.Checking
        scope.launch {
            val cached = resources.isCached(attachment)
            if (resources.canDeliverUiResult()) states[attachment.path] = if (cached) FileDownloadState.Done else FileDownloadState.Idle
        }
    }
    override fun download(attachment: Attachment) { obtain(attachment, false) }
    override fun openOrDownload(attachment: Attachment) { obtain(attachment, true) }
    override fun exportToUserLocation(attachment: Attachment): Boolean {
        if (!resources.canDeliverUiResult()) return false
        obtain(attachment, true, export = true)
        return true
    }
    private fun obtain(attachment: Attachment, open: Boolean, export: Boolean = false) {
        if (!resources.canDeliverUiResult()) return
        scope.launch {
            try {
                states[attachment.path] = FileDownloadState.Downloading(0f)
                val lease = resources.acquire(attachment) { progress ->
                    scope.launch { if (resources.canDeliverUiResult()) states[attachment.path] = FileDownloadState.Downloading(progress) }
                }
                if (!resources.canDeliverUiResult()) { lease.close(); return@launch }
                states[attachment.path] = FileDownloadState.Done
                if (export) native.share(lease, attachment.name) else if (open) native.preview(lease, attachment.name) else lease.close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (resources.canDeliverUiResult()) states[attachment.path] = FileDownloadState.Failed("下载或打开失败，请重试") }
        }
    }
    override fun close() { scope.cancel(); states.clear() }
}

@Composable
internal fun IosAttachmentImage(attachment: Attachment, resources: IosMediaResources, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Decode to the visible viewport, with at most 16 MiB of RGBA pixels per image.
        // The authenticated original remains untouched for sharing and export.
        val maximumPixels = maxOf(constraints.maxWidth, constraints.maxHeight).coerceIn(1, 2048)
        var image by remember(attachment, resources, maximumPixels) { mutableStateOf<UIImage?>(null) }
        var failed by remember(attachment, resources, maximumPixels) { mutableStateOf(false) }
        LaunchedEffect(attachment, resources, maximumPixels) {
            try {
                val lease = resources.acquire(attachment)
                val effectJob = currentCoroutineContext().job
                val display = resources.retainDisplay { effectJob.cancel(); image = null; lease.close() }
                try {
                    image = withContext(Dispatchers.IO) { loadIosDisplayImage(lease.file, maximumPixels) }
                    awaitCancellation()
                } finally { display.close(); image = null; lease.close() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        if (image != null) UIKitView(
            factory = {
                UIImageView().apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                    clipsToBounds = true
                    // 原生视图会拦截触摸，导致外层 Compose 的点击（打开画廊）失效；
                    // 本视图只负责显示，交互全部交还 Compose。
                    setUserInteractionEnabled(false)
                }
            },
            update = { it.image = image },
            onRelease = { it.image = null },
            modifier = Modifier.fillMaxSize(),
        ) else if (failed) Text("图片加载失败") else CircularProgressIndicator()
    }
}

/** ImageIO downsamples during decoding and applies EXIF rotation before UIKit receives the bitmap. */
private fun loadIosDisplayImage(file: PlatformFile, maximumPixels: Int): UIImage {
    val sourceUrl = CFBridgingRetain(NSURL.fileURLWithPath(file.getPath()))
    val source = try { CGImageSourceCreateWithURL(sourceUrl?.reinterpret(), null) } finally { CFRelease(sourceUrl) }
        ?: error("无法读取图片")
    try {
        val thumbnail = memScoped {
            val maximum = alloc<IntVar> { value = maximumPixels }
            val size = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, maximum.ptr)
            val options = checkNotNull(CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null))
            try {
                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, size)
                CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
                CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
            } finally { CFRelease(options); CFRelease(size) }
        } ?: error("无法解码图片")
        try {
            check(CGImageGetWidth(thumbnail) in 1uL..maximumPixels.toULong() &&
                CGImageGetHeight(thumbnail) in 1uL..maximumPixels.toULong()) { "图片解码尺寸超限" }
            return UIImage.imageWithCGImage(thumbnail)
        } finally { CGImageRelease(thumbnail) }
    } finally { CFRelease(source) }
}

@Composable
private fun IosAttachmentVideo(attachment: Attachment, active: Boolean, resources: IosMediaResources, modifier: Modifier) {
    var player by remember(attachment, resources) { mutableStateOf<AVPlayer?>(null) }
    var failed by remember(attachment, resources) { mutableStateOf(false) }
    LaunchedEffect(attachment, resources) {
        try {
            val lease = resources.acquire(attachment)
            val effectJob = currentCoroutineContext().job
            val display = resources.retainDisplay { effectJob.cancel(); player?.pause(); player = null; lease.close() }
            try {
                currentCoroutineContext().ensureActive()
                player = AVPlayer.playerWithURL(NSURL.fileURLWithPath(lease.file.getPath()))
                awaitCancellation()
            } finally { display.close(); player?.pause(); player = null; lease.close() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    LaunchedEffect(player, active) { if (active) player?.play() else player?.pause() }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (player != null) UIKitViewController(
            factory = {
                AVPlayerViewController().apply {
                    // 显示-only：禁用原生交互（自带控制条与触摸吞噬），播放控制走 Compose。
                    view.setUserInteractionEnabled(false)
                }
            },
            update = { it.player = player },
            onRelease = { it.player?.pause(); it.player = null },
            modifier = Modifier.fillMaxSize(),
        ) else if (failed) Text("视频加载失败") else CircularProgressIndicator()
    }
}

internal data class IosGalleryRequest(val items: List<GalleryItem>, val index: Int)
@Composable
internal fun IosGallery(request: IosGalleryRequest, resources: IosMediaResources, files: IosFileDownloads, dismiss: () -> Unit) {
    val foreground by IosApplicationRuntime.foreground.collectAsState()
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MediaGallery(visible = true, items = request.items, initialIndex = request.index, onDismiss = dismiss,
            imageRenderer = { attachment, modifier -> IosAttachmentImage(attachment, resources, modifier) },
            videoRenderer = { attachment, active, modifier -> IosAttachmentVideo(attachment, active && foreground, resources, modifier) },
            onSaveCurrent = { files.exportToUserLocation(it) }, animateEnterExit = false)
    }
}

internal class IosVoicePlayback(private val resources: IosMediaResources, private val beforePlayback: () -> Unit) : VoicePlaybackController, AutoCloseable {
    override var playingUrl: String? by mutableStateOf(null)
        private set
    override var progress: Float by mutableStateOf(0f)
        private set
    private var player: AVPlayer? = null
    private var playback: Job? = null
    private var paused = false
    private var generation = 0L
    override fun toggle(attachment: Attachment, durationSec: Int) {
        if (!resources.canDeliverUiResult()) return
        if (playingUrl == attachment.path) {
            paused = !paused
            if (paused) player?.pause() else player?.play()
            return
        }
        beforePlayback()
        val attempt = ++generation
        playback?.cancel(); player?.pause()
        playingUrl = attachment.path; progress = 0f; paused = false
        playback = resources.scope.launch {
            var lease: IosMediaLease? = null
            var ownedPlayer: AVPlayer? = null
            try {
                lease = resources.acquire(attachment)
                val current = AVPlayer.playerWithURL(NSURL.fileURLWithPath(lease.file.getPath()))
                ownedPlayer = current
                if (generation != attempt) return@launch
                player = current
                AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
                AVAudioSession.sharedInstance().setActive(true, error = null)
                if (!paused) current.play()
                while (isActive) {
                    check(current.status != AVPlayerStatusFailed && current.currentItem?.status != AVPlayerItemStatusFailed) { "无法解码语音" }
                    val position = CMTimeGetSeconds(current.currentTime())
                    val nativeDuration = current.currentItem?.duration?.let(::CMTimeGetSeconds) ?: 0.0
                    val total = nativeDuration.takeIf { it.isFinite() && it > 0 } ?: durationSec.toDouble()
                    if (total > 0 && position.isFinite()) progress = (position / total).toFloat().coerceIn(0f, 1f)
                    if (total > 0 && position >= total - 0.05) break
                    delay(100)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (resources.canDeliverUiResult()) showIosError("语音播放失败，请重试") }
            finally {
                ownedPlayer?.pause(); lease?.close()
                if (generation == attempt) {
                    player = null; playingUrl = null; progress = 0f
                    AVAudioSession.sharedInstance().setActive(false, error = null)
                }
            }
        }
    }
    override fun close() {
        generation++; playback?.cancel(); playback = null; player?.pause(); player = null; playingUrl = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
    }
}
