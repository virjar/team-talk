package com.virjar.tk.app.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 安装 session 资源图的单控制器事务边界。
 *
 * Session 构造可能在另一个 Compose effect 已安装胜者之后、或 TokenStore owner 已被取代之后完成。
 * 这个 gate 让两种失败倾向都显式化，并单独保证认证 session 回调对每个图至多运行一次。
 * [create] 和 close 回调可以切换到阻塞 dispatcher。互斥锁跨越那些挂起点，因此冷启动和 AUTH
 * effect 不可能并发打开两个数据库或安装两个事件投影。发布和 owner 检查仍在调用方的 UI dispatcher 上运行。
 */
internal class AuthSessionInitializationGate<T : Any> {
    private val installationMutex = Mutex()
    private var authenticatedCallbackOwner: T? = null

    suspend fun installOffline(
        current: () -> T?,
        create: suspend () -> T,
        ownerStillCurrent: () -> Boolean,
        publishIfOwnerCurrent: (T) -> Boolean,
        closeConcurrentLoser: suspend (T) -> Unit,
        closeStaleOwner: suspend (T) -> Unit,
    ): OfflineSessionInstallation<T> = installationMutex.withLock {
        current()?.let { active ->
            return if (ownerStillCurrent()) {
                OfflineSessionInstallation.Available(active)
            } else {
                OfflineSessionInstallation.OwnerLost
            }
        }

        val candidate = create()
        current()?.let { winner ->
            closeConcurrentLoser(candidate)
            return OfflineSessionInstallation.ConcurrentWinner(winner)
        }
        if (!ownerStillCurrent()) {
            closeStaleOwner(candidate)
            return OfflineSessionInstallation.OwnerLost
        }
        if (!publishIfOwnerCurrent(candidate)) {
            closeStaleOwner(candidate)
            return OfflineSessionInstallation.OwnerLost
        }
        return OfflineSessionInstallation.Available(candidate)
    }

    suspend fun ensureAuthenticated(
        current: () -> T?,
        create: suspend () -> T,
        ownerStillCurrent: () -> Boolean,
        publishIfOwnerCurrent: (T) -> Boolean,
        closeConcurrentLoser: suspend (T) -> Unit,
        closeStaleOwner: suspend (T) -> Unit,
        onAuthenticated: suspend (T) -> Unit,
    ): OfflineSessionInstallation<T> = installationMutex.withLock {
        var concurrentWinner = false
        var candidateToPublish: T? = null
        val active = current() ?: run {
            val candidate = create()
            val winner = current()
            if (winner != null) {
                closeConcurrentLoser(candidate)
                concurrentWinner = true
                winner
            } else if (!ownerStillCurrent()) {
                closeStaleOwner(candidate)
                return OfflineSessionInstallation.OwnerLost
            } else {
                candidateToPublish = candidate
                candidate
            }
        }
        if (!ownerStillCurrent()) return OfflineSessionInstallation.OwnerLost
        if (authenticatedCallbackOwner !== active) {
            try {
                onAuthenticated(active)
            } catch (failure: Throwable) {
                candidateToPublish?.let { candidate ->
                    try {
                        closeStaleOwner(candidate)
                    } catch (closeFailure: Throwable) {
                        throw mergeClientLifecycleFailures(failure, closeFailure)
                    }
                }
                throw failure
            }
            // 回调可能执行阻塞式的本地投影工作，与此同时后继的 root 认领了凭据。
            // 在该交接之后绝不能把这个图报告为已认证。
            if (!ownerStillCurrent()) {
                // 新候选尚未发布，可以在这里关闭。已有的离线图仍由调用方拥有，
                // 调用方通过完整的平台边界退役它，而不是让它的已发布引用变过期。
                candidateToPublish?.let { closeStaleOwner(it) }
                return OfflineSessionInstallation.OwnerLost
            }
        }
        candidateToPublish?.let { candidate ->
            if (!publishIfOwnerCurrent(candidate)) {
                closeStaleOwner(candidate)
                return OfflineSessionInstallation.OwnerLost
            }
        }
        authenticatedCallbackOwner = active
        return if (concurrentWinner) {
            OfflineSessionInstallation.ConcurrentWinner(active)
        } else {
            OfflineSessionInstallation.Available(active)
        }
    }

    fun forgetAuthenticatedOwner() {
        authenticatedCallbackOwner = null
    }
}

internal sealed interface OfflineSessionInstallation<out T : Any> {
    data class Available<T : Any>(val session: T) : OfflineSessionInstallation<T>

    /** 另一个 effect 安装了图；它自己的状态转换仍然是权威的。 */
    data class ConcurrentWinner<T : Any>(val session: T) : OfflineSessionInstallation<T>

    /** 在构造进行期间，credential owner 发生了变化。 */
    data object OwnerLost : OfflineSessionInstallation<Nothing>
}
