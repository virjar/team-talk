package com.virjar.tk.ui.component

import androidx.compose.runtime.Stable

/**
 * 语音应用内播放控制器（聊天面板级，一次只播一条）。
 *
 * 消除"点击语音 → 调系统播放器弹窗"的反模式：语音在气泡内点击即播、再点暂停，
 * 波形按播放进度着色。平台实现：
 * - Desktop：compose-media-player 的 native 解码引擎（AAC/MP3/WAV 全格式，无 VideoPlayerSurface 纯音频使用）
 * - Android：包装既有 [com.virjar.tk.VoicePlayer]（MediaPlayer）
 *
 * 实现方必须用 Compose 状态支撑两个属性（气泡依赖其订阅刷新）。
 */
@Stable
interface VoicePlaybackController {
    /** 正在播放的语音消息 url（null=空闲；暂停时保留 url 以维持气泡暂停态） */
    val playingUrl: String?

    /** 播放进度 0..1 */
    val progress: Float

    /**
     * 点击语音气泡：同一条 → 播放/暂停切换；不同条 → 切换播放。
     * @param durationSec 消息记录的时长（秒）——部分引擎对音频-only 文件不上报 duration
     *        （桌面 native 引擎实测 duration=0），作为进度分母兜底；有真值时实现可忽略
     */
    fun toggle(url: String, durationSec: Int)
}
