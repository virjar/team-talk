package com.virjar.tk.android

import java.io.File
import java.io.IOException

internal sealed interface VoiceRecordingFinishResult {
    data object Inactive : VoiceRecordingFinishResult

    data class Ready(
        val file: File,
        val startedAt: Long,
    ) : VoiceRecordingFinishResult

    data class Failed(
        val startedAt: Long,
        val invalidOutput: Boolean,
        val stopFailure: Throwable?,
        val releaseFailure: Throwable?,
        val deleteFailure: Throwable?,
    ) : VoiceRecordingFinishResult
}

internal sealed interface VoiceRecordingDiscardResult {
    data object Inactive : VoiceRecordingDiscardResult
    data object Discarded : VoiceRecordingDiscardResult
    data class Failed(val failure: Throwable) : VoiceRecordingDiscardResult
}

internal class VoiceRecordingFileCleanupException :
    IOException("Voice recording fragment still exists after deletion")

internal const val MINIMUM_VOICE_RECORDING_DURATION_MILLIS = 1_000L

/**
 * “过短”只用于指代其余环节都干净、但没有可用输出的录音器收尾。
 * 原生 stop/release 失败即使发生在早期，也仍然是录音失败。
 */
internal fun VoiceRecordingFinishResult.Failed.isTooShort(
    finishedAt: Long,
    minimumDurationMillis: Long = MINIMUM_VOICE_RECORDING_DURATION_MILLIS,
): Boolean =
    invalidOutput &&
        stopFailure == null &&
        releaseFailure == null &&
        (finishedAt - startedAt).coerceAtLeast(0L) < minimumDurationMillis

/**
 * 目标已经消失时，[File.delete] 同样会返回 false。对于幂等的生命周期排空来说，
 * 这属于成功的清理；只有删除失败且目标仍然残留，才算清理缺陷。
 */
internal fun verifyVoiceRecordingFileDeletion(
    deleted: Boolean,
    stillExists: Boolean,
) {
    if (!deleted && stillExists) throw VoiceRecordingFileCleanupException()
}

internal fun deleteVoiceRecordingFile(file: File?) {
    if (file == null) return
    val deleted = file.delete()
    if (!deleted) {
        verifyVoiceRecordingFileDeletion(deleted = false, stillExists = file.exists())
    }
}

/**
 * 单所有者的录音器租约。正常释放会在交出文件之前先停止并释放；
 * 手势取消使用 [discard]，而认证退役使用 [sealAndDiscard]，
 * 在排空当前录音器并删除其残片之前，拒绝迟到的原生发布。
 */
internal class VoiceRecordingLease<R>(
    private val deleteRecordingFile: (File) -> Unit = { file ->
        deleteVoiceRecordingFile(file)
    },
) {
    private data class Active<R>(
        val recorder: R,
        val file: File,
        val startedAt: Long,
    )

    private val lock = Any()
    private var active: Active<R>? = null
    private var sealed = false

    val isActive: Boolean get() = synchronized(lock) { active != null }

    fun attach(recorder: R, file: File, startedAt: Long): Boolean = synchronized(lock) {
        if (sealed || active != null) return@synchronized false
        active = Active(recorder, file, startedAt)
        true
    }

    fun finishForSend(
        stop: (R) -> Unit,
        release: (R) -> Unit,
    ): VoiceRecordingFinishResult {
        val recording = detach() ?: return VoiceRecordingFinishResult.Inactive
        val stopFailure = captureVoiceRecordingFailure { stop(recording.recorder) }
        val releaseFailure = captureVoiceRecordingFailure { release(recording.recorder) }
        val invalidOutput = !recording.file.isFile || recording.file.length() <= 0L

        if (
            stopFailure != null ||
            releaseFailure != null ||
            invalidOutput
        ) {
            val deleteFailure = captureVoiceRecordingFailure {
                deleteRecordingFile(recording.file)
            }
            throwFatalAndroidLifecycleFailures(
                listOfNotNull(stopFailure, releaseFailure, deleteFailure),
            )
            return VoiceRecordingFinishResult.Failed(
                startedAt = recording.startedAt,
                invalidOutput = invalidOutput,
                stopFailure = stopFailure,
                releaseFailure = releaseFailure,
                deleteFailure = deleteFailure,
            )
        }
        return VoiceRecordingFinishResult.Ready(recording.file, recording.startedAt)
    }

    fun discard(
        stop: (R) -> Unit,
        release: (R) -> Unit,
    ): VoiceRecordingDiscardResult {
        val recording = detach() ?: return VoiceRecordingDiscardResult.Inactive
        return discardDetached(recording, stop, release)?.let { failure ->
            VoiceRecordingDiscardResult.Failed(failure)
        }
            ?: VoiceRecordingDiscardResult.Discarded
    }

    /** 在排空当前持有的录音器之前，永久拒绝迟到的录音器发布。 */
    fun sealAndDiscard(
        stop: (R) -> Unit,
        release: (R) -> Unit,
    ) {
        val recording = synchronized(lock) {
            sealed = true
            active.also { active = null }
        } ?: return
        discardDetached(recording, stop, release)?.let { throw it }
    }

    private fun discardDetached(
        recording: Active<R>,
        stop: (R) -> Unit,
        release: (R) -> Unit,
    ): Throwable? {
        val stopFailure = captureVoiceRecordingFailure { stop(recording.recorder) }
        val releaseFailure = captureVoiceRecordingFailure { release(recording.recorder) }
        val deleteFailure = captureVoiceRecordingFailure {
            deleteRecordingFile(recording.file)
        }
        throwFatalAndroidLifecycleFailures(
            listOfNotNull(stopFailure, releaseFailure, deleteFailure),
        )
        return collapseAndroidLifecycleFailures(
            listOfNotNull(stopFailure, releaseFailure, deleteFailure),
        )
    }

    private fun detach(): Active<R>? = synchronized(lock) {
        active.also { active = null }
    }
}

/** 在选定终结录音失败之前，排空每一个原生启动所有者。 */
internal fun cleanupFailedVoiceRecordingStart(
    startFailure: Throwable,
    stop: () -> Unit,
    release: () -> Unit,
    deletePartial: () -> Unit,
): Throwable {
    val failures = mutableListOf(startFailure)
    listOf(stop, release, deletePartial).forEach { cleanup ->
        captureVoiceRecordingFailure(cleanup)?.let(failures::add)
    }
    return checkNotNull(collapseAndroidLifecycleFailures(failures))
}

private fun captureVoiceRecordingFailure(action: () -> Unit): Throwable? = try {
    action()
    null
} catch (failure: Throwable) {
    failure
}

internal enum class VoicePermissionDecision { NO_ACTION, REQUEST_PERMISSION, START_RECORDING }

/**
 * 麦克风权限准入。权限结果只更新就绪状态；
 * 必须再次长按，才能返回 [VoicePermissionDecision.START_RECORDING]。
 */
internal class VoiceRecordPermissionGate {
    private var permissionRequestInFlight = false

    fun enterVoiceMode(permissionGranted: Boolean): VoicePermissionDecision =
        requestPermissionIfNeeded(permissionGranted)

    fun requestStart(permissionGranted: Boolean): VoicePermissionDecision {
        return if (permissionGranted) {
            permissionRequestInFlight = false
            VoicePermissionDecision.START_RECORDING
        } else {
            requestPermissionIfNeeded(permissionGranted = false)
        }
    }

    fun onPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean): VoicePermissionDecision {
        permissionRequestInFlight = false
        return VoicePermissionDecision.NO_ACTION
    }

    fun clear() {
        permissionRequestInFlight = false
    }

    private fun requestPermissionIfNeeded(permissionGranted: Boolean): VoicePermissionDecision {
        if (permissionGranted) {
            permissionRequestInFlight = false
            return VoicePermissionDecision.NO_ACTION
        }
        if (permissionRequestInFlight) return VoicePermissionDecision.NO_ACTION
        permissionRequestInFlight = true
        return VoicePermissionDecision.REQUEST_PERMISSION
    }
}
