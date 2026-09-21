@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.shared.platform.*
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSURL

internal data class IosRecordedVoice(val file: PlatformFile, val durationSeconds: Int)

/** Permission completion never starts recording; only a currently admitted press can do so. */
internal class IosVoiceRecorder(private val resources: IosMediaResources) : AutoCloseable {
    private var recorder: AVAudioRecorder? = null
    private var file: PlatformFile? = null

    fun requestPermission() {
        val session = AVAudioSession.sharedInstance()
        if (session.recordPermission == AVAudioSessionRecordPermissionUndetermined) {
            session.requestRecordPermission { granted ->
                if (!granted) onIosMain { if (resources.canDeliverUiResult()) showIosError("请在系统设置中允许麦克风访问") }
            }
        }
    }
    fun start() {
        if (!resources.canDeliverUiResult() || recorder != null) return
        val session = AVAudioSession.sharedInstance()
        if (session.recordPermission != AVAudioSessionRecordPermissionGranted) {
            requestPermission()
            if (session.recordPermission == AVAudioSessionRecordPermissionDenied) showIosError("请在系统设置中允许麦克风访问")
            return
        }
        val directory = resources.stagingDirectory
        val output = directory.resolve("${platformRandomUuid()}.m4a")
        try {
            check(session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker, null))
            check(session.setActive(true, error = null))
            val recording = AVAudioRecorder(NSURL.fileURLWithPath(output.path), mapOf<Any?, Any>(
                AVFormatIDKey to kAudioFormatMPEG4AAC,
                AVSampleRateKey to 44_100.0,
                AVNumberOfChannelsKey to 1,
                AVEncoderBitRateKey to 64_000,
            ), null)
            check(recording.prepareToRecord() && recording.record()) { "无法开始录音" }
            file = output; recorder = recording
        } catch (_: Exception) {
            output.delete(); session.setActive(false, error = null)
            showIosError("无法开始录音，请检查麦克风权限")
        }
    }
    fun finish(): IosRecordedVoice? {
        val recording = recorder ?: return null
        val output = file ?: return null
        recorder = null; file = null
        val duration = recording.currentTime
        recording.stop()
        AVAudioSession.sharedInstance().setActive(false, error = null)
        if (duration < 0.3 || !output.isFile || output.length() == 0L) {
            output.delete(); showIosError("录音时间太短")
            return null
        }
        return IosRecordedVoice(output, kotlin.math.ceil(duration).toInt())
    }
    override fun close() {
        recorder?.stop(); recorder = null
        file?.delete(); file = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
    }
}
