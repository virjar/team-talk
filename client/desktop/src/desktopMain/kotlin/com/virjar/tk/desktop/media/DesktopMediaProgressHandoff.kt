package com.virjar.tk.desktop.media

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * 把下载线程的进度合并到组合持有的一个 UI 排空器背后。缓存有意在其 IO flight 上调用
 * 传输监听器；触达 Compose 的监听器只能调用 [offer]，绝不能直接修改 Snapshot 状态。
 * 关闭与发布被线性化，因此排队或迟到的进度无法复活已退役的组件。
 */
internal class DesktopMediaProgressHandoff(
    ownerScope: CoroutineScope,
    private val publicationGate: ((() -> Unit) -> Boolean),
    private val publish: (Float) -> Unit,
) : Closeable {
    private val ownerContext = ownerScope.coroutineContext
    private val publicationJob = SupervisorJob(ownerContext[Job])
    private val publicationScope = CoroutineScope(
        ownerContext.minusKey(Job) + publicationJob + CoroutineName("desktop-media-progress"),
    )
    private val lock = Any()
    private var latestProgress: Float? = null
    private var drainScheduled = false
    private var closed = false

    init {
        publicationJob.invokeOnCompletion {
            synchronized(lock) {
                closed = true
                latestProgress = null
                drainScheduled = false
            }
        }
    }

    /** 可能由共享缓存 flight 在任意 worker 线程调用。容量恰好为 1。 */
    fun offer(progress: Float): Boolean {
        val shouldSchedule = synchronized(lock) {
            if (closed) return false
            latestProgress = progress
            if (drainScheduled) {
                false
            } else {
                drainScheduled = true
                true
            }
        }
        if (shouldSchedule) scheduleDrain()
        return true
    }

    private fun scheduleDrain() {
        publicationScope.launch(start = CoroutineStart.DEFAULT) {
            try {
                val progress = synchronized(lock) {
                    if (closed) {
                        latestProgress = null
                        drainScheduled = false
                        return@launch
                    }
                    latestProgress.also { latestProgress = null }
                }
                if (progress != null) {
                    publicationGate {
                        synchronized(lock) {
                            if (!closed) publish(progress)
                        }
                    }
                }
                val reschedule = synchronized(lock) {
                    when {
                        closed -> {
                            latestProgress = null
                            drainScheduled = false
                            false
                        }
                        latestProgress == null -> {
                            drainScheduled = false
                            false
                        }
                        else -> true
                    }
                }
                if (reschedule) scheduleDrain()
            } catch (failure: Throwable) {
                synchronized(lock) {
                    latestProgress = null
                    drainScheduled = false
                }
                throw failure
            }
        }
    }

    override fun close() {
        val shouldCancel = synchronized(lock) {
            if (closed) return@synchronized false
            closed = true
            latestProgress = null
            drainScheduled = false
            true
        }
        if (shouldCancel) publicationJob.cancel()
    }
}
