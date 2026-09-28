package com.virjar.tk.app.ui.call

import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Android 通话视频渲染句柄（由 AndroidCallEngine 推送，actual Composable 消费）。
 * 放在 app androidMain 而非壳层，保证 :client:android 引擎与共享 UI 的依赖方向单一。
 */
class AndroidRemoteVideoHandle(val track: VideoTrack, val eglContext: EglBase.Context)

class AndroidLocalVideoHandle(val track: VideoTrack, val eglContext: EglBase.Context)
