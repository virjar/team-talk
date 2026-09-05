package com.virjar.tk.android

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal data class VoicePlaybackSnapshot(
    val url: String?,
    val cacheNamespace: String?,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val error: String?,
)

internal class VoicePlaybackCapability internal constructor(internal val generation: Long)

internal data class VoicePlaybackRetirement(
    val task: Job?,
    val player: VoicePlaybackHandle?,
) {
    internal companion object {
        val EMPTY = VoicePlaybackRetirement(task = null, player = null)
    }
}

internal sealed interface VoicePlaybackBeginResult {
    data object Closed : VoicePlaybackBeginResult

    data class Started(
        val capability: VoicePlaybackCapability,
        val task: Job,
        val previous: VoicePlaybackRetirement,
    ) : VoicePlaybackBeginResult

    data class Failed(
        val failure: Throwable,
        val previous: VoicePlaybackRetirement,
    ) : VoicePlaybackBeginResult
}

internal sealed interface VoicePlaybackToggleResult {
    data object Miss : VoicePlaybackToggleResult
    data object Toggled : VoicePlaybackToggleResult
    data class Failed(
        val failure: Throwable,
        val retirement: VoicePlaybackRetirement,
    ) : VoicePlaybackToggleResult
}

internal sealed interface VoicePlaybackPublishResult {
    data object Published : VoicePlaybackPublishResult
    data object Rejected : VoicePlaybackPublishResult
    data class Failed(val failure: Throwable) : VoicePlaybackPublishResult
}

internal sealed interface VoicePlaybackStopResult {
    data object OwnerMismatch : VoicePlaybackStopResult
    data class Retired(val retirement: VoicePlaybackRetirement) : VoicePlaybackStopResult
}

/**
 * 进程级语音播放槽位的可线性化所有者。
 *
 * 请求只有在它的惰性 Job 已在同一把锁下完成安装后，才会获得一个 capability。[stop] 和 [close]
 * 会在把资源返回给调用方之前使该 capability 失效。因此，已准备好的播放器要么在退役之前完成发布
 * 并被退役流程返回，要么失去发布资格并仍归发起请求的协程所有；它绝不会落入两个清理所有者之间的
 * 真空地带。页面停止被引用性地限定在确切的已认证媒体会话上，因为缓存命名空间有意在令牌轮换后
 * 仍然存活，并且可能在同一个 uid 再次登录后被复用。
 */
internal class VoicePlaybackOwnershipGate {
    private val lock = ReentrantLock()
    private var generation = 0L
    private var closed = false
    private var current: ActiveRequest? = null
    private var lastError: String? = null

    fun acceptsNewRequests(): Boolean = lock.withLock { !closed }

    fun snapshot(): VoicePlaybackSnapshot = lock.withLock {
        val request = current
        VoicePlaybackSnapshot(
            url = request?.url,
            cacheNamespace = request?.cacheNamespace,
            isPlaying = request?.isPlaying == true,
            isLoading = request?.isLoading == true,
            error = lastError,
        )
    }

    fun progress(reportFailure: (Exception) -> Unit): Float = lock.withLock {
        val player = current?.player ?: return@withLock 0f
        try {
            val duration = player.durationMs
            if (duration > 0) {
                (player.currentPositionMs.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            try {
                reportFailure(failure)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 进度诊断只是尽力而为，绝不能破坏播放所有者。
            }
            0f
        }
    }

    fun toggleIfCurrent(
        owner: Any,
        cacheNamespace: String,
        url: String,
    ): VoicePlaybackToggleResult = lock.withLock {
        val request = current
        val player = request?.player
        if (
            request == null ||
            request.owner !== owner ||
            request.cacheNamespace != cacheNamespace ||
            request.url != url ||
            player == null
        ) {
            return@withLock VoicePlaybackToggleResult.Miss
        }

        try {
            if (request.isPlaying) {
                player.pause()
                request.isPlaying = false
            } else {
                player.start()
                request.isPlaying = true
            }
            VoicePlaybackToggleResult.Toggled
        } catch (failure: Throwable) {
            val retirement = retireCurrentLocked()
            lastError = failure.message ?: "语音播放失败"
            VoicePlaybackToggleResult.Failed(failure, retirement)
        }
    }

    fun begin(
        owner: Any,
        cacheNamespace: String,
        url: String,
        createLazyTask: (VoicePlaybackCapability) -> Job,
    ): VoicePlaybackBeginResult = lock.withLock {
        if (closed) return@withLock VoicePlaybackBeginResult.Closed

        val previous = retireCurrentLocked()
        val capability = VoicePlaybackCapability(nextGenerationLocked())
        val request = ActiveRequest(capability, owner, cacheNamespace, url)
        current = request
        lastError = null
        try {
            val task = createLazyTask(capability)
            request.task = task
            VoicePlaybackBeginResult.Started(capability, task, previous)
        } catch (failure: Throwable) {
            retireCurrentLocked()
            lastError = failure.message ?: "语音播放失败"
            VoicePlaybackBeginResult.Failed(failure, previous)
        }
    }

    fun isAdmitted(capability: VoicePlaybackCapability): Boolean = lock.withLock {
        isCurrentLocked(capability)
    }

    fun publish(
        capability: VoicePlaybackCapability,
        player: VoicePlaybackHandle,
    ): VoicePlaybackPublishResult = lock.withLock {
        val request = current
        if (request == null || !isCurrentLocked(capability)) {
            return@withLock VoicePlaybackPublishResult.Rejected
        }

        request.player = player
        request.isLoading = false
        request.isPlaying = false
        try {
            player.start()
            request.isPlaying = true
            VoicePlaybackPublishResult.Published
        } catch (failure: Throwable) {
            request.player = null
            retireCurrentLocked()
            lastError = failure.message ?: "语音播放失败"
            VoicePlaybackPublishResult.Failed(failure)
        }
    }

    fun complete(
        capability: VoicePlaybackCapability,
        player: VoicePlaybackHandle,
    ): VoicePlaybackHandle? = lock.withLock {
        val request = current
        if (request == null || !isCurrentLocked(capability) || request.player !== player) {
            return@withLock null
        }
        retireCurrentLocked().player
    }

    fun fail(capability: VoicePlaybackCapability, message: String) = lock.withLock {
        if (isCurrentLocked(capability)) {
            retireCurrentLocked()
            lastError = message
        }
    }

    fun abandon(capability: VoicePlaybackCapability): VoicePlaybackRetirement = lock.withLock {
        if (isCurrentLocked(capability)) retireCurrentLocked() else VoicePlaybackRetirement.EMPTY
    }

    fun stop(owner: Any): VoicePlaybackStopResult = lock.withLock {
        val request = current
        if (request != null && request.owner !== owner) {
            return@withLock VoicePlaybackStopResult.OwnerMismatch
        }
        VoicePlaybackStopResult.Retired(retireCurrentLocked())
    }

    fun close(): VoicePlaybackRetirement = lock.withLock {
        if (closed) return@withLock VoicePlaybackRetirement.EMPTY
        closed = true
        retireCurrentLocked()
    }

    private fun isCurrentLocked(capability: VoicePlaybackCapability): Boolean =
        !closed && capability.generation == generation && current?.capability === capability

    private fun retireCurrentLocked(): VoicePlaybackRetirement {
        val retired = current
        current = null
        lastError = null
        nextGenerationLocked()
        return VoicePlaybackRetirement(retired?.task, retired?.player)
    }

    private fun nextGenerationLocked(): Long {
        check(generation < Long.MAX_VALUE) { "Voice playback generation exhausted" }
        generation += 1L
        return generation
    }

    private class ActiveRequest(
        val capability: VoicePlaybackCapability,
        val owner: Any,
        val cacheNamespace: String,
        val url: String,
        var task: Job? = null,
        var player: VoicePlaybackHandle? = null,
        var isPlaying: Boolean = false,
        var isLoading: Boolean = true,
    )
}

/** 尝试执行每一个原生清理动作，上报普通状态失败，并保留致命异常本体。 */
internal fun disposeVoicePlaybackHandle(
    player: VoicePlaybackHandle,
    stopFirst: Boolean,
    reportOrdinaryFailure: (action: String, failure: Exception) -> Unit,
): Throwable? {
    val failures = mutableListOf<Pair<String, Throwable>>()
    if (stopFirst) {
        captureVoicePlaybackFailure(player::stop)?.let { failures += "stop player" to it }
    }
    captureVoicePlaybackFailure(player::release)?.let { failures += "release player" to it }

    val diagnosticFailures = mutableListOf<Throwable>()
    failures.forEach { (action, failure) ->
        if (failure is Exception && failure !is CancellationException) {
            try {
                reportOrdinaryFailure(action, failure)
            } catch (diagnosticFailure: Throwable) {
                diagnosticFailures += diagnosticFailure
            }
        }
    }
    val observedFailures = failures.map { it.second } + diagnosticFailures
    val fatal = observedFailures.firstOrNull(::isFatalVoicePlaybackFailure) ?: return null
    observedFailures.forEach { failure -> addSuppressedVoicePlaybackFailure(fatal, failure) }
    return fatal
}

/** 绝不能仅仅因为请求了取消，就运行停止回调。 */
internal fun notifyAfterVoicePlaybackTask(task: Job?, callback: (() -> Unit)?) {
    if (callback == null) return
    if (task == null || task.isCompleted) {
        callback()
    } else {
        task.invokeOnCompletion { callback() }
    }
}

internal fun captureVoicePlaybackFailure(action: () -> Unit): Throwable? = try {
    action()
    null
} catch (failure: Throwable) {
    failure
}

internal fun mergeVoicePlaybackFailures(primary: Throwable?, additional: Throwable?): Throwable? {
    val failures = listOfNotNull(primary, additional)
    if (failures.isEmpty()) return null
    val selected = failures.firstOrNull(::isFatalVoicePlaybackFailure) ?: failures.first()
    failures.forEach { failure -> addSuppressedVoicePlaybackFailure(selected, failure) }
    return selected
}

internal fun isFatalVoicePlaybackFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

private fun addSuppressedVoicePlaybackFailure(primary: Throwable, additional: Throwable) {
    if (primary !== additional && primary.suppressedExceptions.none { it === additional }) {
        primary.addSuppressed(additional)
    }
}
