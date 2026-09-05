package com.virjar.tk.app.client

/**
 * 在本地认证 session 构造或平台发布失败后，退役每一个 owner。
 * 引发失败的异常与清理参与同一套致命/取消优先级。
 */
internal fun retireFailedAuthSessionInitialization(
    failure: Throwable,
    message: String,
    cause: AuthControllerRetirementCause,
    endAuthenticatedSession: (message: String, cause: AuthControllerRetirementCause) -> Unit,
    disconnectTransport: () -> Unit,
) {
    check(
        cause == AuthControllerRetirementCause.OFFLINE_SESSION_INITIALIZATION_FAILURE ||
            cause == AuthControllerRetirementCause.AUTHENTICATED_SESSION_INITIALIZATION_FAILURE ||
            cause == AuthControllerRetirementCause.PLATFORM_AUTHENTICATED_CALLBACK_FAILURE,
    ) { "Session initialization retirement requires a local resource failure cause" }
    val drain = AuthControllerRetirementDrain()
    drain.record("session initialization", failure)
    drain.release("authenticated session") { endAuthenticatedSession(message, cause) }
    drain.release("transport", disconnectTransport)
    drain.throwIfFatal()
}
