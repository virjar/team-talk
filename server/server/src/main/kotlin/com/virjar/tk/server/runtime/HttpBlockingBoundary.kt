package com.virjar.tk.server.runtime

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.util.pipeline.intercept
import kotlinx.coroutines.CancellationException

/**
 * 把每条普通的下游 HTTP 调用流水线移出受保护的 Netty EventLoop。精确的
 * 健康路径是唯一例外，因此只能包含显式非阻塞或
 * 显式卸载的工作。
 *
 * 在 `routing { ... }` 之前安装此项。饱和是立即的、分配有界的 503；
 * 被拒绝的路由流水线既不启动，也不被执行器保留。
 */
internal fun Application.installHttpBlockingBoundary(executor: HttpBlockingExecutor) {
    intercept(ApplicationCallPipeline.Call) {
        if (call.request.path() == HEALTH_CHECK_PATH) {
            proceed()
            return@intercept
        }
        when (executor.tryExecute { proceed() }) {
            is HttpBlockingExecution.Completed -> Unit
            HttpBlockingExecution.Rejected -> {
                call.request.receiveChannel().cancel(
                    CancellationException(HTTP_BLOCKING_OVERLOAD_BODY_CANCELLATION_MESSAGE),
                )
                finish()
                call.response.headers.append(HttpHeaders.RetryAfter, OVERLOAD_RETRY_AFTER_SECONDS)
                call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}

internal const val HEALTH_CHECK_PATH = "/health"
internal const val HTTP_BLOCKING_OVERLOAD_BODY_CANCELLATION_MESSAGE =
    "Rejected request at HTTP blocking admission"
private const val OVERLOAD_RETRY_AFTER_SECONDS = "1"
