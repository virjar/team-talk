package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.RemoteFailureClassification
import com.virjar.tk.shared.RemoteFailureClassifier
import com.virjar.tk.shared.client.TransportUnavailableException
import com.virjar.tk.protocol.rpc.RpcStatusException

/**
 * 重试一份持久快照，同时不允许一个永久失败的条目饿死无关的条目。
 * 认证失效是部署级的，因此仍然是唯一的快速失败结果。
 */
internal suspend fun <T> retryPendingMirrors(
    snapshot: List<T>,
    mirror: suspend (T) -> Outcome<Unit>,
): Outcome<Unit> {
    var firstRetryableFailure: Outcome.Failure? = null
    var firstTerminalFailure: Outcome.Failure? = null
    for (pending in snapshot) {
        when (val result = mirror(pending)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> {
                when (RemoteFailureClassifier.classify(result.error)) {
                    RemoteFailureClassification.AUTH_EXPIRED -> {
                        // 认证失效是部署级的。被拒绝的会话上不可能有任何后续条目取得进展，
                        // 调用方必须让那个精确的 owner 退场。
                        return result
                    }

                    RemoteFailureClassification.RETRYABLE -> {
                        if (firstRetryableFailure == null) firstRetryableFailure = result
                    }

                    RemoteFailureClassification.TERMINAL -> {
                        if (firstTerminalFailure == null) firstTerminalFailure = result
                    }
                }
            }
        }
    }
    // 后续的瞬时失败必须让 worker 的有界退避保持活跃，即使更早的
    // 条目收到了确定性响应。否则那个后续的持久条目会永远等待
    // 一次无关的重连边缘。
    return firstRetryableFailure ?: firstTerminalFailure ?: Outcome.Success(Unit)
}

/**
 * 只有完成的、操作作用域的 4xx 响应才能证明重试这条不可变命令
 * 永远不可能产生不同的答案。传输歧义、认证失效、限流、服务端
 * 故障与本地编解码缺陷都必须保留其持久代次，以供恢复或诊断。
 */
internal fun Throwable.isDefinitiveReliableCommandRejection(): Boolean = when (this) {
    is RpcStatusException -> status.isDefinitiveReliableCommandRejectionStatus()
    is AppError.Business -> code.isDefinitiveReliableCommandRejectionStatus()
    else -> false
}

/** 当该失败使持久重放保持完整时，前台命令在本地被接受。 */
internal fun Throwable.isRetryableReliableCommandFailure(): Boolean = when (this) {
    is TransportUnavailableException -> true
    is RpcStatusException ->
        RemoteFailureClassifier.classifyStatus(status) == RemoteFailureClassification.RETRYABLE
    is AppError ->
        RemoteFailureClassifier.classify(this) == RemoteFailureClassification.RETRYABLE
    else -> false
}

private fun Int.isDefinitiveReliableCommandRejectionStatus(): Boolean =
    this in 400..499 && this != 401 && this != 403 && this != 408 && this != 429

/** 一个失败的有界命令族不能饿死无关的可靠 outbox。 */
internal suspend fun retryIndependentPendingFamilies(
    vararg retry: suspend () -> Outcome<Unit>,
): Outcome<Unit> {
    var firstRetryableFailure: Outcome.Failure? = null
    var firstTerminalFailure: Outcome.Failure? = null
    for (family in retry) {
        when (val result = family()) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> {
                when (RemoteFailureClassifier.classify(result.error)) {
                    RemoteFailureClassification.AUTH_EXPIRED -> return result
                    RemoteFailureClassification.RETRYABLE -> {
                        if (firstRetryableFailure == null) firstRetryableFailure = result
                    }
                    RemoteFailureClassification.TERMINAL -> {
                        if (firstTerminalFailure == null) firstTerminalFailure = result
                    }
                }
            }
        }
    }
    return firstRetryableFailure ?: firstTerminalFailure ?: Outcome.Success(Unit)
}
