package com.virjar.tk.util

fun Throwable.toUserMessage(): String = when (this) {
    is java.net.ConnectException -> "网络连接失败，请检查网络"
    is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
    else -> message ?: "操作失败，请稍后重试"
}
