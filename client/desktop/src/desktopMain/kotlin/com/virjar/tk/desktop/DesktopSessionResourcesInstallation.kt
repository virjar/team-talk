package com.virjar.tk.desktop

import com.virjar.tk.desktop.media.DesktopSessionResources
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 为某一个确切的已认证 Desktop 会话打开平台资源的结果。 */
internal sealed interface DesktopSessionResourcesInstallationResult<out U : Any> {
    data class Ready<U : Any>(val resources: DesktopSessionResources, val uiOwner: U) :
        DesktopSessionResourcesInstallationResult<U>
    data class Failed(val failure: Throwable) : DesktopSessionResourcesInstallationResult<Nothing>
    data object Superseded : DesktopSessionResourcesInstallationResult<Nothing>
}

/** 过期的失败按钮不能在 Compose 移除它之前发起第二次安装。 */
internal fun admitDesktopSessionResourcesRetry(
    currentResult: DesktopSessionResourcesInstallationResult<*>?,
    clearFailure: () -> Unit,
    retry: () -> Unit,
) {
    if (currentResult !is DesktopSessionResourcesInstallationResult.Failed) return
    clearFailure()
    retry()
}

/**
 * 一个 Desktop 会话的小型构造交接。
 *
 * 媒体和草稿 writer 在 [storageDispatcher] 上构建。[install] 由 Compose Main 调用，
 * 回到 Main 复验确切会话后才组装导航；全部成功后只发布一个 Ready。
 * 认证退役绑定完成前，本对象负责销毁未发布的 UI、保留草稿并释放全部候选资源。
 * U 只表示随候选组装的 UI owner，不规定或共享平台 UI 状态。
 */
internal class DesktopSessionResourcesInstallation<U : Any>(
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val createResources: () -> DesktopSessionResourceCandidate,
    private val discardUnboundUi: (U) -> Unit,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var installing = false
    private var pending: DesktopSessionResourceCandidate? = null
    private var pendingUi: U? = null
    private var installed: DesktopSessionResourceCandidate? = null
    private var installedUi: U? = null
    private var lifecycleBound = false

    suspend fun install(
        ownerStillCurrent: () -> Boolean,
        createUi: (DesktopSessionResourceCandidate) -> U,
    ): DesktopSessionResourcesInstallationResult<U> {
        synchronized(lock) {
            if (closed) return DesktopSessionResourcesInstallationResult.Superseded
            installed?.let {
                return DesktopSessionResourcesInstallationResult.Ready(it.media, checkNotNull(installedUi))
            }
            check(!installing) { "Desktop session resources are already being installed" }
            installing = true
        }

        val callerJob = currentCoroutineContext()[Job]
        try {
            withContext(storageDispatcher) {
                val candidate = createResources()
                val retained = synchronized(lock) {
                    if (!closed && installing && pending == null && installed == null) {
                        pending = candidate
                        true
                    } else {
                        false
                    }
                }
                if (!retained) candidate.close()
            }

            val result = withContext(NonCancellable) {
                val published = synchronized(lock) {
                    val candidate = pending
                    if (candidate != null && !closed && callerJob?.isActive != false && ownerStillCurrent()) {
                        // 与 close 互斥：UI 构造期间不得释放它正在借用的候选资源。
                        // 不在这里打开磁盘，也不把 Compose 状态带到 storage dispatcher。
                        val ui = createUi(candidate)
                        pendingUi = ui
                        if (!closed && callerJob?.isActive != false && ownerStillCurrent()) {
                            pending = null
                            pendingUi = null
                            installed = candidate
                            installedUi = ui
                            installing = false
                            DesktopSessionResourcesInstallationResult.Ready(candidate.media, ui)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }

                if (published != null) {
                    published
                } else {
                    val closeFailure = closePendingOnStorage()
                    if (closeFailure is CancellationException || closeFailure != null && closeFailure !is Exception) {
                        throw closeFailure
                    }
                    closeFailure?.let { DesktopSessionResourcesInstallationResult.Failed(it) }
                        ?: DesktopSessionResourcesInstallationResult.Superseded
                }
            }
            callerJob?.ensureActive()
            return result
        } catch (cancelled: CancellationException) {
            val closeFailure = closeAfterCancelledInstallation()
            throw if (closeFailure == null) cancelled else mergeDesktopLifecycleFailures(cancelled, closeFailure)
        } catch (failure: Throwable) {
            val closeFailure = closePendingOnStorage()
            val terminal = if (closeFailure == null) failure else mergeDesktopLifecycleFailures(failure, closeFailure)
            if (terminal is CancellationException || terminal !is Exception) throw terminal
            return DesktopSessionResourcesInstallationResult.Failed(terminal)
        }
    }

    /**
     * 登记退役 owner 与移交草稿责任共用 close 的锁。registry 只登记引用、不会同步执行退役；
     * 另一线程即使立刻 claim 该绑定，其 close 也只能在交接完成后继续。
     */
    fun bindLifecycle(
        resources: DesktopSessionResources,
        bindRetirement: () -> Closeable,
    ): Closeable? = synchronized(lock) {
        if (closed || installed?.media !== resources) return@synchronized null
        val binding = bindRetirement()
        lifecycleBound = true
        binding
    }

    /** 已发布的候选对象由 Compose 销毁持有，直到退役流程接走它为止。 */
    fun abandonIfUnbound(): Throwable? {
        val candidate = synchronized(lock) {
            if (lifecycleBound) return null
            closed = true
            installing = false
            takeCandidateLocked(closeDrafts = true)
        }
        return closeCandidateFailure(candidate)
    }

    /** 认证退役路径在销毁导航之后调用此方法。 */
    override fun close() {
        val candidate = synchronized(lock) {
            closed = true
            installing = false
            takeCandidateLocked(closeDrafts = !lifecycleBound).also { lifecycleBound = false }
        }
        closeCandidateFailure(candidate)?.let { throw it }
    }

    private suspend fun closePendingOnStorage(): Throwable? {
        val candidate = synchronized(lock) {
            installing = false
            pending?.let { ClosingCandidate(it, pendingUi, closeDrafts = true) }.also {
                pending = null
                pendingUi = null
            }
        } ?: return null
        return closeUnboundCandidateOnStorage(candidate)
    }

    private suspend fun closeAfterCancelledInstallation(): Throwable? {
        val candidate = synchronized(lock) {
            closed = true
            installing = false
            if (lifecycleBound) {
                pending?.let { ClosingCandidate(it, pendingUi, closeDrafts = true) }.also {
                    pending = null
                    pendingUi = null
                }
            } else {
                takeCandidateLocked(closeDrafts = true)
            }
        }
        if (candidate == null) return null
        return closeUnboundCandidateOnStorage(candidate)
    }

    private inner class ClosingCandidate(
        val resources: DesktopSessionResourceCandidate,
        val uiOwner: U?,
        val closeDrafts: Boolean,
    )

    private fun takeCandidateLocked(closeDrafts: Boolean): ClosingCandidate? =
        (pending ?: installed)?.let {
            ClosingCandidate(it, (pendingUi ?: installedUi).takeIf { closeDrafts }, closeDrafts)
        }.also {
            pending = null
            pendingUi = null
            installed = null
            installedUi = null
        }

    private fun discardUiFailure(candidate: ClosingCandidate): Throwable? =
        candidate.uiOwner?.let { runCatching { discardUnboundUi(it) }.exceptionOrNull() }

    private fun closeResourcesFailure(candidate: ClosingCandidate, failure: Throwable?): Throwable? {
        val closeFailure = runCatching {
            if (candidate.closeDrafts) candidate.resources.close() else candidate.resources.media.close()
        }.exceptionOrNull()
        return if (closeFailure == null) failure else mergeDesktopLifecycleFailures(failure, closeFailure)
    }

    private fun closeCandidateFailure(candidate: ClosingCandidate?): Throwable? =
        candidate?.let { closeResourcesFailure(it, discardUiFailure(it)) }

    private suspend fun closeUnboundCandidateOnStorage(candidate: ClosingCandidate): Throwable? =
        withContext(NonCancellable) {
            // 导航先在调用方 Main 退休，再在 storage dispatcher 排空草稿和媒体。
            val failure = discardUiFailure(candidate)
            withContext(storageDispatcher) { closeResourcesFailure(candidate, failure) }
        }
}
