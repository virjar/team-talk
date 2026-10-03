package com.virjar.tk.shared.call

import com.virjar.tk.protocol.model.CallEndReason
import com.virjar.tk.protocol.model.IceServer
import kotlinx.coroutines.flow.StateFlow

/** 通话方向。 */
enum class CallDirection { INCOMING, OUTGOING }

/** UI 可观察的通话阶段；ENDED 携带原因，由界面确认后 [CallCenter.dismissEnded] 清除。 */
enum class CallPhase { RINGING, CONNECTING, ACTIVE, ENDED }

/** 会话级单一活跃通话的 UI 状态；null 表示空闲。 */
data class CallViewState(
    val callId: String,
    val peerUid: String,
    val direction: CallDirection,
    val video: Boolean,
    val phase: CallPhase,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    val endReason: CallEndReason? = null,
)

/**
 * 平台媒体引擎回调：协商产物上送（经信令中继发给对端）与媒体面状态。
 * 全部回调在引擎自己的线程上触发，[CallCenter] 负责收敛到状态流。
 */
interface CallMediaObserver {
    fun onLocalDescription(isOffer: Boolean, sdp: String)
    fun onLocalCandidate(candidate: com.virjar.tk.protocol.model.CallSignalBody.IceCandidate)
    fun onMediaConnected()
    fun onMediaFailed()

    /**
     * 本地视频采集确定无输出（无设备、启动失败、或启动后首帧看门狗超时）。不中断通话：
     * 远端画面与音频不受影响，UI 在本地预览位提示原因。
     * 默认空实现保证旧引擎/测试观察者无需跟进。
     */
    fun onLocalCameraStalled(reason: String) {}
}

/**
 * WebRTC 媒体引擎的平台抽象：三端各自绑定 libwebrtc（Android 官方 API、桌面 webrtc-java、
 * iOS ObjC framework），commonMain 只依赖本接口与协议信令模型。
 *
 * 语义：[start] 采集并建立 PeerConnection；主叫在收到 ACCEPTED 后由引擎 createOffer，
 * 被叫在 [onRemoteSessionDescription] 收到 offer 后自动 answer；ICE 候选双向透传。
 * 渲染句柄是平台对象（Android 为绑定的渲染 View/Texture，桌面为 ImageBitmap，iOS 为
 * 平台帧句柄），由各端 actual Composable 消费。
 */
interface CallMediaEngine : AutoCloseable {
    val remoteVideo: StateFlow<Any?>
    val localVideo: StateFlow<Any?>

    fun start(video: Boolean, iceServers: List<IceServer>, observer: CallMediaObserver)

    /** 主叫在收到对端接听后调用：创建 offer 并经 [CallMediaObserver.onLocalDescription] 上送。 */
    fun initiateOffer()
    fun onRemoteSessionDescription(isOffer: Boolean, sdp: String)
    fun onRemoteCandidate(candidate: com.virjar.tk.protocol.model.CallSignalBody.IceCandidate)
    fun setMuted(muted: Boolean)
    fun setSpeakerphone(enabled: Boolean)
    fun switchCamera()

    /**
     * 本机是否存在可切换的多个摄像头（移动端前后摄）。单摄像头平台（如桌面只接
     * 一个 USB 摄像头）UI 应隐藏切换入口；默认 false，引擎按设备枚举覆写。
     */
    val canSwitchCamera: Boolean get() = false
}

/** 平台引擎工厂：会话激活后由平台壳经 [CallCenter.bindEngineFactory] 注入一次。 */
fun interface CallMediaEngineFactory {
    fun create(): CallMediaEngine
}
