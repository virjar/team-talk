package com.virjar.tk.app.telemetry

internal actual fun platformMediaFailureReason(failure: Throwable): MediaFailureReason? = when (failure) {
    is java.net.ConnectException, is java.net.NoRouteToHostException,
    is java.net.SocketTimeoutException, is java.net.UnknownHostException,
    is java.net.SocketException -> MediaFailureReason.NETWORK
    is java.io.IOException -> MediaFailureReason.IO
    else -> null
}
