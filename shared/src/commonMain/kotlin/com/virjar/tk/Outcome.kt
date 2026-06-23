package com.virjar.tk

/**
 * 类型化的操作结果，替代散落各处的 try-catch。
 *
 * 使用方式：
 * ```
 * suspend fun getMessage(seq: Long): Outcome<Message> = outcome {
 *     val resp = rpcClient.invoke(...)
 *     // ...
 * }
 *
 * when (val result = getMessage(42)) {
 *     is Outcome.Success -> show(result.value)
 *     is Outcome.Failure -> when (result.error) {
 *         is AppError.AuthExpired -> logout()
 *         is AppError.Network -> showOfflineBanner()
 *         else -> showError(result.error.message)
 *     }
 * }
 * ```
 *
 * 设计原则：
 * - **不静默吞错**：Repository 返回 Failure 由调用方决定降级策略
 * - **认证失效停而非重试**：[AppError.AuthExpired] 是一等公民，向上传播触发登出
 * - sealed class 编译器强制 exhaustive when
 */
sealed class Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>()
    data class Failure(val error: AppError) : Outcome<Nothing>()

    inline fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    /** 成功返回 value，失败返回 null（用于"尽力尝试"场景）。 */
    fun getOrNull(): T? = (this as? Success)?.value

    /** 失败时用 transform 降级，返回最终值（用于网络失败回退本地缓存等场景）。 */
    inline fun recover(transform: (AppError) -> @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> transform(error)
    }

    /** 失败时抛出 AppError（它继承 Throwable），用于桥接到仍用异常的代码路径。 */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error
    }
}

/**
 * 错误类型。继承 Throwable 以便桥接到异常处理路径（如 log.error("", error)）。
 *
 * 语义对齐（服务端 status code）：
 * - [Network]: 连接断开、未连接、IOException
 * - [Timeout]: RPC 超时（status=504）
 * - [AuthExpired]: 认证失效（status=401），触发登出而非重试
 * - [Business]: 业务错误（status 非 0/401/504）
 * - [Unknown]: 未分类异常
 */
sealed class AppError(
    message: String,
) : Exception(message) {
    data object Network : AppError("网络连接异常")
    data object Timeout : AppError("请求超时")
    data object AuthExpired : AppError("认证失效，请重新登录")
    data class Business(val code: Int, override val message: String) : AppError(message)
    /** 编解码错误（客户端 decode 越界）—— FATAL 级别，协议紊乱，需开发者修复 */
    data class FatalCodec(override val message: String) : AppError(message)
    data class Unknown(override val cause: Throwable) : AppError(cause.message ?: "未知错误")
}

/**
 * outcome { ... } 构造器：把可能抛异常的代码块转成 Outcome。
 *
 * 捕获规则：
 * - [AppError] 直接透传（已类型化）
 * - [IllegalStateException]("Not connected") / [kotlinx.coroutines.CancellationException] → [AppError.Network]
 * - 其他 Throwable → [AppError.Unknown]
 *
 * 注意：[kotlinx.coroutines.TimeoutCancellationException] 不在此捕获，因为 RpcClient
 * 内部已用 withTimeoutOrNull 转成 status=504 的 ResponsePayload，不会作为异常传播。
 */
suspend inline fun <T> outcome(crossinline block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: AppError) {
    Outcome.Failure(e)
} catch (e: IndexOutOfBoundsException) {
    // 客户端 decode 越界 = 编解码紊乱（与服务端字段不一致），FATAL 级别
    Outcome.Failure(AppError.FatalCodec("数据解析错误（客户端与服务端编解码不一致）：${e.message}"))
} catch (e: IllegalStateException) {
    Outcome.Failure(AppError.Network)
} catch (e: kotlinx.coroutines.CancellationException) {
    Outcome.Failure(AppError.Network)
} catch (e: Throwable) {
    Outcome.Failure(AppError.Unknown(e))
}
