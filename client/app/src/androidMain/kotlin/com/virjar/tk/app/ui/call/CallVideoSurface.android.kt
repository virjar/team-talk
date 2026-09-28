package com.virjar.tk.app.ui.call

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Android 通话视频渲染：SurfaceViewRenderer 挂到引擎推送的 VideoTrack 上。
 * 同一 track 的句柄保持稳定，渲染器生命周期随 Composable 释放。
 */
@Composable
actual fun CallVideoSurface(handle: Any?, mirror: Boolean, modifier: Modifier) {
    when (handle) {
        is AndroidRemoteVideoHandle -> WebRtcVideoRenderer(handle.track, handle.eglContext, mirror, modifier)
        is AndroidLocalVideoHandle -> WebRtcVideoRenderer(handle.track, handle.eglContext, mirror, modifier)
        else -> androidx.compose.foundation.layout.Box(modifier.background(Color.Black))
    }
}

@Composable
private fun WebRtcVideoRenderer(
    track: org.webrtc.VideoTrack,
    eglContext: org.webrtc.EglBase.Context,
    mirror: Boolean,
    modifier: Modifier,
) {
    // 以 track 为 key：远端轨道替换时重建渲染器，避免悬挂旧 sink
    val renderer = remember(track) {
        SurfaceViewRenderer(null).apply {
            init(eglContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {}
                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
            })
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(mirror)
            setZOrderMediaOverlay(mirror)
        }
    }
    DisposableEffect(track, renderer) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer); renderer.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                addView(
                    renderer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
    )
}
