package com.virjar.tk.app.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 阻塞式 [TokenStore] owner 认领的进程内排序。
 *
 * 组合在不触碰磁盘的情况下预留位置。针对同一底层存储的认领随后在 worker dispatcher 上串行化。
 * 较新的预留可能在较旧的磁盘写入仍在运行时到达；较旧的结果保持休眠，由较新的预留执行最终的
 * 持久认领。取消那个较新的预留会唤醒上一个存活的预留再次认领，这样一次短暂的组合
 * 就不会把仍然挂载的根滞留在一个它从未发布的 generation 后面。
 */
internal class AuthCredentialOwnerClaimCoordinator {
    private val lock = Any()
    private val namespaces = mutableMapOf<String, NamespaceClaims>()
    private var nextReservationId = 0L

    private class NamespaceClaims {
        val claimMutex = Mutex()
        val reservations = mutableListOf<AuthCredentialOwnerClaimLease>()
        val changes = MutableStateFlow(0L)
    }

    fun reserve(namespace: String): AuthCredentialOwnerClaimLease = synchronized(lock) {
        require(namespace.isNotBlank()) { "TokenStore owner namespace must not be blank" }
        check(nextReservationId < Long.MAX_VALUE) { "Credential owner reservation generation exhausted" }
        val claims = namespaces.getOrPut(namespace, ::NamespaceClaims)
        AuthCredentialOwnerClaimLease(
            coordinator = this,
            namespace = namespace,
            reservationId = ++nextReservationId,
            claimMutex = claims.claimMutex,
            changes = claims.changes,
        ).also(claims.reservations::add)
            .also { claims.changes.value += 1L }
    }

    suspend fun <T : Any> claim(
        lease: AuthCredentialOwnerClaimLease,
        blockingDispatcher: CoroutineDispatcher,
        blockingClaim: () -> T,
    ): AuthCredentialOwnerClaimResult<T> {
        try {
            while (true) {
                val observedChange = lease.changes.value
                val admitted = lease.claimMutex.withLock {
                    if (!isCurrent(lease)) return@withLock null
                    val claimed = withContext(blockingDispatcher) { blockingClaim() }
                    if (isCurrent(lease)) AuthCredentialOwnerClaimResult.Claimed(claimed) else null
                }
                if (admitted != null) return admitted
                if (isReleased(lease)) return AuthCredentialOwnerClaimResult.Superseded
                // 让更早的、仍挂载的根保持休眠，同时其后继者占用该槽位。如果那个后继者只是
                // 短暂的并释放了它的预留，重试会执行最终的持久认领，而不是把存活的根滞留在加载界面上。
                lease.changes.first { it != observedChange }
            }
        } finally {
            pruneEmptyNamespace(lease)
        }
    }

    internal fun isCurrent(lease: AuthCredentialOwnerClaimLease): Boolean = synchronized(lock) {
        !lease.released && namespaces[lease.namespace]?.reservations?.lastOrNull() === lease
    }

    internal fun publishIfCurrent(
        lease: AuthCredentialOwnerClaimLease,
        publication: () -> Boolean,
    ): Boolean = synchronized(lock) {
        if (lease.released || namespaces[lease.namespace]?.reservations?.lastOrNull() !== lease) {
            false
        } else {
            publication()
        }
    }

    private fun isReleased(lease: AuthCredentialOwnerClaimLease): Boolean = synchronized(lock) {
        lease.released
    }

    private fun pruneEmptyNamespace(lease: AuthCredentialOwnerClaimLease) = synchronized(lock) {
        val claims = namespaces[lease.namespace] ?: return@synchronized
        if (
            claims.claimMutex === lease.claimMutex &&
            claims.reservations.isEmpty() &&
            !claims.claimMutex.isLocked
        ) {
            namespaces.remove(lease.namespace, claims)
        }
    }

    internal fun release(lease: AuthCredentialOwnerClaimLease) = synchronized(lock) {
        if (lease.released) return@synchronized
        lease.released = true
        val claims = namespaces[lease.namespace] ?: return@synchronized
        claims.reservations.remove(lease)
        claims.changes.value += 1L
        if (claims.reservations.isEmpty() && !claims.claimMutex.isLocked) {
            namespaces.remove(lease.namespace, claims)
        }
    }
}

internal class AuthCredentialOwnerClaimLease internal constructor(
    private val coordinator: AuthCredentialOwnerClaimCoordinator,
    internal val namespace: String,
    internal val reservationId: Long,
    internal val claimMutex: Mutex,
    internal val changes: MutableStateFlow<Long>,
) : AutoCloseable {
    internal var released: Boolean = false

    fun isCurrent(): Boolean = coordinator.isCurrent(this)

    fun publishIfCurrent(publication: () -> Boolean): Boolean =
        coordinator.publishIfCurrent(this, publication)

    override fun close() = coordinator.release(this)
}

internal sealed interface AuthCredentialOwnerClaimResult<out T : Any> {
    data class Claimed<T : Any>(val owner: T) : AuthCredentialOwnerClaimResult<T>
    data object Superseded : AuthCredentialOwnerClaimResult<Nothing>
}
