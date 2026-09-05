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
internal sealed interface DesktopSessionResourcesInstallationResult {
    data class Ready(val resources: DesktopSessionResources) : DesktopSessionResourcesInstallationResult
    data class Failed(val failure: Throwable) : DesktopSessionResourcesInstallationResult
    data object Superseded : DesktopSessionResourcesInstallationResult
}

/** 过期的失败按钮不能在 Compose 移除它之前发起第二次安装。 */
internal fun admitDesktopSessionResourcesRetry(
    currentResult: DesktopSessionResourcesInstallationResult?,
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
 * 磁盘支持的资源在 [storageDispatcher] 上构建。调用方在 Compose Main 上恢复，
 * 以便在本 owner 发布候选对象之前重新检查确切的会话。在认证 UI 退役绑定安装完成之前，
 * 关闭候选对象的责任一直由本对象承担。
 */
internal class DesktopSessionResourcesInstallation(
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val createResources: () -> DesktopSessionResources,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var installing = false
    private var pending: DesktopSessionResources? = null
    private var installed: DesktopSessionResources? = null
    private var lifecycleBound = false

    suspend fun install(
        ownerStillCurrent: () -> Boolean,
    ): DesktopSessionResourcesInstallationResult {
        synchronized(lock) {
            if (closed) return DesktopSessionResourcesInstallationResult.Superseded
            installed?.let { return DesktopSessionResourcesInstallationResult.Ready(it) }
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
                val currentBeforeCheck = callerJob?.isActive != false
                val ownerCurrent = currentBeforeCheck && ownerStillCurrent()
                val currentAfterCheck = callerJob?.isActive != false
                val published = if (ownerCurrent && currentAfterCheck) {
                    synchronized(lock) {
                        pending?.takeIf { !closed }?.also { candidate ->
                            pending = null
                            installed = candidate
                            installing = false
                        }
                    }
                } else {
                    null
                }

                if (published != null) {
                    DesktopSessionResourcesInstallationResult.Ready(published)
                } else {
                    val closeFailure = closePendingOnStorage()
                    if (closeFailure != null && closeFailure !is Exception) throw closeFailure
                    closeFailure?.let(DesktopSessionResourcesInstallationResult::Failed)
                        ?: DesktopSessionResourcesInstallationResult.Superseded
                }
            }
            callerJob?.ensureActive()
            return result
        } catch (cancelled: CancellationException) {
            closeAfterCancelledInstallation()?.let(cancelled::addSuppressedSafely)
            throw cancelled
        } catch (failure: Throwable) {
            closePendingOnStorage()?.let(failure::addSuppressedSafely)
            if (failure !is Exception) throw failure
            return DesktopSessionResourcesInstallationResult.Failed(failure)
        }
    }

    /** 只有在确切会话的同步退役绑定安装完成之后才调用。 */
    fun markLifecycleBound(resources: DesktopSessionResources): Boolean = synchronized(lock) {
        if (closed || installed !== resources) return@synchronized false
        lifecycleBound = true
        true
    }

    /** 已发布的候选对象由 Compose 销毁持有，直到退役流程接走它为止。 */
    fun abandonIfUnbound(): Throwable? {
        val candidate = synchronized(lock) {
            if (lifecycleBound) return null
            closed = true
            installing = false
            takeCandidateLocked()
        }
        return closeCandidateFailure(candidate)
    }

    /** 认证退役路径在销毁导航之后调用此方法。 */
    override fun close() {
        val candidate = synchronized(lock) {
            closed = true
            installing = false
            lifecycleBound = false
            takeCandidateLocked()
        }
        closeCandidateFailure(candidate)?.let { throw it }
    }

    private suspend fun closePendingOnStorage(): Throwable? {
        val candidate = synchronized(lock) {
            installing = false
            pending.also { pending = null }
        } ?: return null
        return withContext(NonCancellable + storageDispatcher) {
            runCatching { candidate.close() }.exceptionOrNull()
        }
    }

    private suspend fun closeAfterCancelledInstallation(): Throwable? {
        val candidate = synchronized(lock) {
            closed = true
            installing = false
            if (lifecycleBound) {
                pending.also { pending = null }
            } else {
                takeCandidateLocked()
            }
        }
        if (candidate == null) return null
        return withContext(NonCancellable + storageDispatcher) {
            closeCandidateFailure(candidate)
        }
    }

    private fun takeCandidateLocked(): DesktopSessionResources? = (pending ?: installed).also {
        pending = null
        installed = null
    }
}

private fun closeCandidateFailure(candidate: DesktopSessionResources?): Throwable? =
    candidate?.let { runCatching { it.close() }.exceptionOrNull() }

private fun Throwable.addSuppressedSafely(additional: Throwable) {
    if (this !== additional && suppressed.none { it === additional }) addSuppressed(additional)
}
