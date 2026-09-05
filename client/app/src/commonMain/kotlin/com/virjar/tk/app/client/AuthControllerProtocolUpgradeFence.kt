package com.virjar.tk.app.client
import com.virjar.tk.shared.client.AuthenticationFailure
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.logUnhandledError

/** 在这个二进制再次离线引导之前，持久化服务器已淘汰该客户端版本的事实。 */
internal fun persistObservedProtocolRefusal(
    credentialOwner: AuthControllerCredentialOwner,
    clientProtocolVersion: Int,
    failure: AuthenticationFailure,
) {
    if (failure.kind != AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED ||
        !failure.requiresClientUpgrade
    ) return
    var persistenceFailure: Throwable? = null
    try {
        // 这个增量式的部署事实刻意在同一个部署的 TokenStore owner 交接中存续。
        // 返回 false 意味着部署本身已经变了。
        credentialOwner.markProtocolVersionRejected(clientProtocolVersion)
    } catch (failure: Throwable) {
        persistenceFailure = failure
        try {
            // 无法持久化围栏的存储绝不能把这个 owner 的凭据留给被拒绝二进制的后续离线引导。
            credentialOwner.clearStoredLogin()
        } catch (clearFailure: Throwable) {
            persistenceFailure = mergeClientLifecycleFailures(persistenceFailure, clearFailure)
        }
    }
    persistenceFailure?.let { observedFailure ->
        if (isFatalClientLifecycleFailure(observedFailure)) throw observedFailure
        logUnhandledError("ProtocolUpgradeFence", observedFailure)
    }
}
