package com.virjar.tk.app.client

import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.ClientSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 围绕本地优先的发布边界，排序持久账号的启动。
 *
 * [prepareRemoteOwner] 只能安装一个休眠的逻辑传输 owner。返回的能力在 [startRemote]
 * 被调用之前绝不能解析或连接。本函数在调用该启动边界之前先发布一个可用的本地图，
 * 因此即使远程 AUTH 立即失败，观察到的也是一个已经挂载的离线工作区。
 */
internal suspend fun <Session : Any, PreparedRemote : Any> bootstrapPersistedLocalSession(
    prepareRemoteOwner: () -> PreparedRemote,
    awaitRemoteOwner: suspend () -> Unit,
    installLocalSession: suspend () -> OfflineSessionInstallation<Session>,
    publishLocalReady: (Session) -> Unit,
    startRemote: (PreparedRemote) -> Unit,
): OfflineSessionInstallation<Session> {
    val preparedRemote = prepareRemoteOwner()
    awaitRemoteOwner()
    val installation = installLocalSession()
    val available = when (installation) {
        is OfflineSessionInstallation.Available -> installation.session
        is OfflineSessionInstallation.ConcurrentWinner -> installation.session
        OfflineSessionInstallation.OwnerLost -> null
    }
    if (available != null) {
        publishLocalReady(available)
        startRemote(preparedRemote)
    }
    return installation
}

/** 作为一个 owner 限定的本地事务，打开并发布持久化的 ClientSession。 */
internal suspend fun installPersistedClientSession(
    initialization: AuthSessionInitializationGate<ClientSession>,
    current: () -> ClientSession?,
    create: () -> ClientSession,
    ownerClaimLease: AuthCredentialOwnerClaimLease,
    durableOwnerStillCurrent: () -> Boolean,
    publish: (ClientSession) -> Unit,
    constructionDispatcher: CoroutineDispatcher,
): OfflineSessionInstallation<ClientSession> {
    fun ownerStillCurrent(): Boolean =
        ownerClaimLease.isCurrent() && durableOwnerStillCurrent()

    if (!ownerStillCurrent()) return OfflineSessionInstallation.OwnerLost
    return withContext(NonCancellable) {
        initialization.installOffline(
            current = current,
            create = { withContext(constructionDispatcher) { create() } },
            ownerStillCurrent = ::ownerStillCurrent,
            publishIfOwnerCurrent = { candidate ->
                ownerClaimLease.publishIfCurrent {
                    if (!durableOwnerStillCurrent()) {
                        false
                    } else {
                        publish(candidate)
                        true
                    }
                }
            },
            closeConcurrentLoser = { candidate ->
                withContext(constructionDispatcher) {
                    candidate.close(
                        reason = SessionEndReason.PROCESS_REPLACED,
                        disconnectTransport = false,
                    )
                }
            },
            closeStaleOwner = { candidate ->
                withContext(constructionDispatcher) {
                    candidate.close(reason = SessionEndReason.PROCESS_REPLACED)
                }
            },
        )
    }
}
