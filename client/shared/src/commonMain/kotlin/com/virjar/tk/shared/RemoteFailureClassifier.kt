package com.virjar.tk.shared

/**
 * 针对已分类的传输层或远程服务故障，给出的操作级动作。
 *
 * [AUTH_EXPIRED] 对已认证会话而言是终局性的，而 [TERMINAL] 仅对当前操作是终局性的。
 * 持有持久化本地事实的调用方可以为后续显式的用户编辑或连接恢复边缘情况保留它们，
 * 但不得对这两种终局分类中的任何一种安排自动重试。
 */
internal enum class RemoteFailureClassification {
    RETRYABLE,
    AUTH_EXPIRED,
    TERMINAL,
}

/** SDK 中唯一的权威入口，负责对类型化 RPC/HTTP 错误及正向的远程状态族进行分类。 */
internal object RemoteFailureClassifier {
    fun classify(error: AppError): RemoteFailureClassification = when (error) {
        AppError.Network,
        AppError.Timeout -> RemoteFailureClassification.RETRYABLE

        AppError.AuthExpired -> RemoteFailureClassification.AUTH_EXPIRED
        is AppError.Business -> classifyStatus(error.code)
        is AppError.FatalCodec,
        is AppError.Unknown -> RemoteFailureClassification.TERMINAL
    }

    /**
     * 对已完成的远程响应进行分类。负数的本地传输伪状态码被刻意排除在本契约之外，
     * 仍由具体的传输层调用方负责处理。
     */
    fun classifyStatus(status: Int): RemoteFailureClassification = when {
        status == 401 -> RemoteFailureClassification.AUTH_EXPIRED
        status == 408 || status == 429 || status in 500..599 -> RemoteFailureClassification.RETRYABLE
        else -> RemoteFailureClassification.TERMINAL
    }
}
