package com.virjar.tk.util

import com.virjar.tk.client.ApiException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 结构化错误类型，统一网络/API/认证等错误场景。
 * UI 层可根据类型显示不同的提示文案和操作按钮。
 */
sealed class AppError {
    /** 网络不可达（连接失败、DNS 解析失败等） */
    data class Network(val detail: String = "网络连接失败，请检查网络设置") : AppError()

    /** 请求超时 */
    data class Timeout(val detail: String = "请求超时，请稍后重试") : AppError()

    /** 认证失败（401 / token 过期） */
    data class Auth(val detail: String = "登录已过期，请重新登录") : AppError()

    /** 服务端返回的业务错误 */
    data class Api(val code: Int, val httpStatus: Int, override val message: String) : AppError()

    /** 未知错误 */
    data class Unknown(val throwable: Throwable?, override val message: String = "操作失败，请稍后重试") : AppError()

    open val message: String get() = when (this) {
        is Network -> detail
        is Timeout -> detail
        is Auth -> detail
        is Api -> message
        is Unknown -> message
    }

    /** 是否可通过重试解决 */
    val isRetryable: Boolean get() = this is Network || this is Timeout

    /** 是否需要重新登录 */
    val requiresRelogin: Boolean get() = this is Auth
}

/** 将任意 Throwable 映射为结构化 AppError */
fun Throwable.toAppError(): AppError = when (this) {
    is ApiException -> when (httpStatus) {
        401 -> AppError.Auth()
        in 500..599 -> AppError.Api(code, httpStatus, "服务器繁忙，请稍后重试 ($httpStatus)")
        else -> AppError.Api(code, httpStatus, message)
    }
    is ConnectException -> AppError.Network()
    is UnknownHostException -> AppError.Network("无法连接到服务器，请检查网络")
    is SocketTimeoutException -> AppError.Timeout()
    else -> AppError.Unknown(this, message ?: "操作失败")
}
