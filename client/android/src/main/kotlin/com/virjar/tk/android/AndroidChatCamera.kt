package com.virjar.tk.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virjar.tk.shared.platform.platformRandomUuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 聊天短视频约定上限：到点自动停（内测反馈：几十秒足够，无需专业录制）。 */
internal const val CHAT_CAMERA_MAX_VIDEO_MILLIS = 60_000L

/** 按住该时长后进入录像；短于此视为点按拍照。 */
internal const val CHAT_CAMERA_RECORD_START_DELAY_MILLIS = 250L

internal sealed interface ChatCameraResult {
    data class Photo(val file: File) : ChatCameraResult
    data class Video(val file: File) : ChatCameraResult
}

/**
 * 方向角 → Surface 旋转（CameraX 官方映射）。方向来自设备的物理朝向（OrientationEventListener
 * 的传感器角度），与界面/系统旋转锁定无关——锁定竖屏后横持拍摄仍按宽大于高记录（内测反馈）。
 * [degrees] 为 ORIENTATION_UNKNOWN(-1) 时返回自然竖屏。
 */
internal fun physicalRotationBucket(degrees: Int): Int = when (degrees) {
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

/**
 * 应用内相机全屏对话框：点按拍照、长按录像（松手结束，60 秒自动停），拍摄即震动，
 * 拍完后停在预览确认层（重拍/发送）。相机初始化失败时回调 [onUnavailable]，由宿主
 * 决定回落方式（当前为系统相机录制意图）。产物文件写入应用缓存，发送管线负责最终清理。
 */
@Composable
internal fun AndroidChatCameraDialog(
    cacheDirectory: File,
    onResult: (ChatCameraResult) -> Unit,
    onUnavailable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var confirmed by remember { mutableStateOf<ChatCameraResult?>(null) }
        var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
        var providerReady by remember { mutableStateOf<ProcessCameraProvider?>(null) }
        var physicalRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
        var recording by remember { mutableStateOf<Recording?>(null) }
        var recordingStartAt by remember { mutableStateOf<Long?>(null) }
        var recordingMillis by remember { mutableStateOf(0L) }
        val scope = rememberCoroutineScope()
        var pressJob by remember { mutableStateOf<Job?>(null) }

        val vibrator = remember { context.getSystemService(Vibrator::class.java) }
        // 震动是拍摄的手感增强，不是关键路径：任何系统层拒绝都不允许让拍摄崩溃。
        fun vibrate(effect: Int) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(effect))
                } else {
                    vibrator.vibrate(40)
                }
            } catch (_: Exception) {
            }
        }

        val preview = remember { Preview.Builder().build() }
        val imageCapture = remember {
            ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        }
        val videoCapture = remember {
            VideoCapture.withOutput(
                Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                    )
                    .build(),
            )
        }

        DisposableEffect(Unit) {
            val listener = object : OrientationEventListener(context.applicationContext) {
                override fun onOrientationChanged(degrees: Int) {
                    if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    physicalRotation = physicalRotationBucket(degrees)
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }

        LaunchedEffect(Unit) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    providerReady = future.get()
                } catch (_: Exception) {
                    onUnavailable()
                }
            }, mainExecutor)
        }

        // 物理方向变化只更新采集用例的目标旋转：元数据随真实方向写入，预览仍由屏幕显示。
        LaunchedEffect(providerReady, lensFacing, physicalRotation) {
            val provider = providerReady ?: return@LaunchedEffect
            imageCapture.targetRotation = physicalRotation
            videoCapture.setTargetRotation(physicalRotation)
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, videoCapture)
            } catch (_: Exception) {
                onUnavailable()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                recording?.stop()
                providerReady?.unbindAll()
            }
        }

        fun stopRecording() {
            recordingStartAt = null
            recording?.stop()
        }

        @SuppressLint("MissingPermission")
        fun startRecording(): Boolean {
            val provider = providerReady ?: return false
            if (recording != null) return false
            vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
            val file = File(cacheDirectory, "video-${platformRandomUuid()}.mp4")
            val pending = videoCapture.output.prepareRecording(
                context,
                FileOutputOptions.Builder(file).build(),
            )
            val withAudio = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val guarded = if (withAudio) pending.withAudioEnabled() else pending
            recording = guarded.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> recordingStartAt = android.os.SystemClock.elapsedRealtime()
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        recordingStartAt = null
                        if (event.hasError()) {
                            com.virjar.tk.shared.log.AppLog.fault(
                                "ChatMedia",
                                "video finalize error",
                                event.cause,
                            )
                            file.delete()
                        } else {
                            com.virjar.tk.shared.log.AppLog.trace("ChatMedia", "video captured: ${file.length()}B ${file.path}")
                            confirmed = ChatCameraResult.Video(file)
                        }
                    }
                    else -> Unit
                }
            }
            return recording != null
        }

        fun takePhoto() {
            vibrate(VibrationEffect.EFFECT_CLICK)
            val file = File(cacheDirectory, "photo-${platformRandomUuid()}.jpg")
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        com.virjar.tk.shared.log.AppLog.trace("ChatMedia", "photo captured: ${file.length()}B ${file.path}")
                        confirmed = ChatCameraResult.Photo(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        com.virjar.tk.shared.log.AppLog.fault("ChatMedia", "photo capture error", exception)
                        file.delete()
                    }
                },
            )
        }

        // 录制计时与上限：到 CHAT_CAMERA_MAX_VIDEO_MILLIS 自动停止。
        LaunchedEffect(recordingStartAt) {
            val start = recordingStartAt ?: return@LaunchedEffect
            while (true) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                if (elapsed >= CHAT_CAMERA_MAX_VIDEO_MILLIS) {
                    stopRecording()
                    break
                }
                recordingMillis = elapsed
                delay(100)
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val result = confirmed) {
                null -> {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                preview.setSurfaceProvider(surfaceProvider)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 录制计时
                    val activeStart = recordingStartAt
                    if (activeStart != null) {
                        Text(
                            "${recordingMillis / 1000}.${recordingMillis % 1000 / 100} s",
                            color = Color.White,
                            fontSize = 16.sp,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                        )
                    }
                    Icon(
                        Icons.Filled.FlipCameraAndroid,
                        contentDescription = "翻转摄像头",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 40.dp, end = 24.dp)
                            .size(28.dp)
                            .clickable(enabled = recording == null) { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            } },
                    )
                    // 快门：点按拍照；按住超过阈值进入录像，松手停止。
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .size(78.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color.White, CircleShape)
                            .background(
                                if (recording != null) Color(0xFFE53935) else Color.White.copy(alpha = 0.9f),
                                CircleShape,
                            )
                            .pointerInput(providerReady) {
                                // 未注册 onLongPress 时，detectTapGestures 对任意时长的
                                // 抬起都会触发 onTap：长按录像松手后必须抑制拍照，
                                // 否则慢一拍的 Photo 会覆盖已 Finalize 的 Video。
                                var pressStartedRecording = false
                                detectTapGestures(
                                    onTap = { if (!pressStartedRecording) takePhoto() },
                                    onPress = {
                                        pressStartedRecording = false
                                        pressJob = scope.launch {
                                            delay(CHAT_CAMERA_RECORD_START_DELAY_MILLIS)
                                            pressStartedRecording = startRecording()
                                        }
                                        try {
                                            awaitRelease()
                                        } finally {
                                            pressJob?.cancel()
                                            stopRecording()
                                        }
                                    },
                                )
                            },
                    )
                }

                is ChatCameraResult.Photo -> {
                    val bitmap = remember(result.file) { decodePreviewBitmap(result.file) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "拍摄照片",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    CaptureConfirmActions(
                        enabled = true,
                        onRetake = { result.file.delete(); confirmed = null },
                        onSend = { onResult(result) },
                    )
                }

                is ChatCameraResult.Video -> {
                    val player = remember(result.file) {
                        ExoPlayer.Builder(context).build().apply {
                            setMediaItem(MediaItem.fromUri(Uri.fromFile(result.file)))
                            repeatMode = Player.REPEAT_MODE_ONE
                            prepare()
                            playWhenReady = true
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                this.player = player
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    DisposableEffect(player) { onDispose { player.release() } }
                    CaptureConfirmActions(
                        enabled = true,
                        onRetake = { result.file.delete(); confirmed = null },
                        onSend = { onResult(result) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CaptureConfirmActions(
    enabled: Boolean,
    onRetake: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onRetake, enabled = enabled) { Text("重拍", color = Color.White) }
        Button(onClick = onSend, enabled = enabled) { Text("发送") }
    }
}

/** 结果预览用降采样解码：预览不需要全分辨率位图。 */
private fun decodePreviewBitmap(file: File, target: Int = 2048): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}
