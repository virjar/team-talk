package deployment

import java.io.File

internal const val STAGED_UPLOAD_PARTIAL_DIRECTORY = ".teamtalk-rsync-partial"
// 一次不稳定的长距离上传，可能需要多次部分传输才能完成 170+ MB 的服务端包。
internal const val STAGED_UPLOAD_MAX_ATTEMPTS = 12
internal const val STAGED_UPLOAD_RETRY_DELAY_MILLIS = 2_000L
internal const val STAGED_UPLOAD_OPERATION_DRAIN_TIMEOUT_MILLIS = 10 * 60_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

private val recoverableStagedUploadExitCodes = setOf(
    10, // rsync socket 读写
    12, // rsync 协议数据流
    30, // rsync 数据超时
    35, // rsync 连接超时
    255, // SSH 传输
)

private fun isStagedUploadOperationConflict(failure: ExternalProcessException): Boolean =
    failure is ProcessExitException &&
        failure.exitCode == DEPLOYMENT_OPERATION_CONFLICT_EXIT_CODE

internal fun isRecoverableStagedUploadFailure(failure: ExternalProcessException): Boolean =
    failure is ProcessTimeoutException ||
        failure is ProcessExitException && failure.exitCode in recoverableStagedUploadExitCodes

/**
 * 只重试那些可能留下有效 rsync 部分传输数据的失败。调用方在整个循环中保持相同的
 * 部署租约和暂存目标，因此每次重试都受同一代号（generation）围栏保护，
 * 并且可以复用远端已经接收的字节。
 */
internal fun runResumableStagedUpload(
    label: String,
    maxAttempts: Int = STAGED_UPLOAD_MAX_ATTEMPTS,
    retryDelayMillis: Long = STAGED_UPLOAD_RETRY_DELAY_MILLIS,
    operationDrainTimeoutMillis: Long = STAGED_UPLOAD_OPERATION_DRAIN_TIMEOUT_MILLIS,
    sleep: (Long) -> Unit = { delayMillis -> Thread.sleep(delayMillis) },
    nanoTime: () -> Long = System::nanoTime,
    requireOwner: () -> Unit = {},
    upload: () -> Unit,
) {
    require(label.isNotBlank()) { "Staged upload label must not be blank" }
    require(maxAttempts > 0) { "Staged upload attempts must be positive" }
    require(retryDelayMillis >= 0L) { "Staged upload retry delay must not be negative" }
    require(operationDrainTimeoutMillis >= 0L) {
        "Staged upload operation drain timeout must not be negative"
    }

    var transferAttempt = 1
    var operationDrainStartedNanos: Long? = null
    while (true) {
        try {
            requireOwner()
            upload()
            return
        } catch (failure: ExternalProcessException) {
            // 传输退出也可能意味着持有租约的 SSH 进程已死。在把这次传输归类为
            // 同一代号（generation）下可续传之前，先校验租约持有者。
            requireOwner()
            if (isStagedUploadOperationConflict(failure)) {
                val now = nanoTime()
                val started = operationDrainStartedNanos ?: now.also {
                    operationDrainStartedNanos = it
                }
                val elapsedMillis = (now - started).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
                if (elapsedMillis >= operationDrainTimeoutMillis) throw failure
                println(
                    "  Previous staged upload is still finishing remotely; waiting before " +
                        "retrying $label ...",
                )
                sleep(retryDelayMillis)
                continue
            }
            operationDrainStartedNanos = null
            if (!isRecoverableStagedUploadFailure(failure) || transferAttempt == maxAttempts) {
                throw failure
            }
            transferAttempt++
            println(
                "  Staged upload was interrupted; preserving partial data and retrying " +
                    "$label ($transferAttempt/$maxAttempts) ...",
            )
            sleep(retryDelayMillis)
        }
    }
}

internal fun uploadStagedServerDistribution(
    label: String,
    distribution: File,
    host: String,
    user: String,
    port: Int,
    stagedPath: String,
) {
    requireActiveRemoteDeploymentGuard(host, user, port)
    val arguments = upgradeRsyncArguments(distribution, user, host, port, stagedPath)
    runResumableStagedUpload(
        label = label,
        requireOwner = { requireActiveRemoteDeploymentGuard(host, user, port) },
        upload = {
            localChecked(
                label,
                arguments,
                timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
            )
        },
    )
}
