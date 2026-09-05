package com.virjar.tk.app.client

import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.ClientSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** 登录界面在本地终止后所需要的窄身份端口。 */
internal interface AuthLogoutIdentityOwner {
    fun retireAuthResults()
    fun clearStoredLogin()
    fun clearUserIdentity(reason: String?)
}

/** 窄 session 端口，把原始的注销 RPC 能力密封在 [ClientSession] 内部。 */
internal interface AuthUserLogoutSession {
    fun quiesce()
    suspend fun complete(shouldDisconnectTransport: () -> Boolean)
    fun close(disconnectTransport: Boolean)
    fun recordFailure(stage: String, failure: Throwable)
}

internal class ClientSessionUserLogoutOwner(
    val session: ClientSession,
) : AuthUserLogoutSession {
    override fun quiesce() {
        session.beginUserLogoutRetirement()
    }

    override suspend fun complete(shouldDisconnectTransport: () -> Boolean) {
        session.completeUserLogoutRetirement(shouldDisconnectTransport).getOrThrow()
    }

    override fun close(disconnectTransport: Boolean) {
        session.close(
            reason = SessionEndReason.USER_LOGOUT,
            disconnectTransport = disconnectTransport,
        )
    }

    override fun recordFailure(stage: String, failure: Throwable) {
        session.recordRetirementFailure(stage, failure)
    }
}

/**
 * 同步执行注销不可逆的本地半边，然后拥有可选的远程 RPC。
 *
 * UI/session 图在本函数返回之前即被退役，即使在离线时也是如此。只有密封的、
 * 最大努力式的注销能力可以继续在 [controllerScope] 中进行。generation 谓词隔离了迟到的完成，
 * 使其不会断开替换登录的连接；fallback 即使在 scope 在派发之前被取消时也会关闭该能力。
 */
internal class AuthUserLogoutRetirement(
    private val controllerScope: CoroutineScope,
    private val identityOwner: AuthLogoutIdentityOwner,
    private val disconnectTransport: () -> Unit,
) {
    fun retire(
        sessionOwner: AuthUserLogoutSession?,
        isGenerationCurrent: () -> Boolean,
        retireLocalSessionState: () -> Unit,
        markRemoteRetirement: () -> Unit,
        clearRemoteRetirement: () -> Unit,
    ): Job? {
        val drain = AuthControllerRetirementDrain()
        val quiesceFailure = try {
            sessionOwner?.quiesce()
            null
        } catch (failure: Throwable) {
            drain.record("session quiesce", failure)
            failure
        }

        // 发布登录界面并不依赖于存储或网络清理。
        drain.release("local session state", retireLocalSessionState)
        drain.release("AUTH result admission", identityOwner::retireAuthResults)
        val localCleanupCheckpoint = drain.checkpoint()
        drain.release("stored login", identityOwner::clearStoredLogin)
        drain.release("user identity") { identityOwner.clearUserIdentity(null) }
        drain.firstFailureSince(localCleanupCheckpoint)?.let { localFailure ->
            drain.release("local cleanup diagnostics") {
                sessionOwner?.recordFailure(
                    "User logout local cleanup completed with " +
                        "${drain.failureCount - localCleanupCheckpoint} failure(s)",
                    localFailure,
                )
            }
        }

        if (sessionOwner == null) {
            drain.release("transport", disconnectTransport)
            drain.throwIfFatal()
            return null
        }
        if (quiesceFailure != null || drain.hasFatalFailure) {
            drain.release("session close") {
                sessionOwner.close(disconnectTransport = isGenerationCurrent())
            }
            drain.release("forced-close diagnostics") {
                sessionOwner.recordFailure(
                    "User logout failed before asynchronous retirement; forced terminal close",
                    checkNotNull(drain.primaryFailure),
                )
            }
            clearRemoteRetirement()
            drain.throwIfFatal()
            return null
        }

        markRemoteRetirement()
        return try {
            controllerScope.launchRetirementWithFallback(
                fallback = {
                    sessionOwner.close(disconnectTransport = isGenerationCurrent())
                },
            ) {
                try {
                    sessionOwner.complete(isGenerationCurrent)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 离线注销仍然在本地终止。该能力在 fallback 中关闭。
                } finally {
                    clearRemoteRetirement()
                }
            }
        } catch (startupFailure: Throwable) {
            drain.record("retirement job startup", startupFailure)
            drain.release("session close") {
                sessionOwner.close(disconnectTransport = isGenerationCurrent())
            }
            drain.release("job-start diagnostics") {
                sessionOwner.recordFailure(
                    "User logout retirement job failed to start",
                    checkNotNull(drain.primaryFailure),
                )
            }
            clearRemoteRetirement()
            drain.throwIfFatal()
            null
        }
    }
}
