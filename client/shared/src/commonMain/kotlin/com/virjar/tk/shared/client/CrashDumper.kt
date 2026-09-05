package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.AppLog
import java.io.File

private data class CrashOwnerIdentity(
    val deploymentFingerprint: String,
    val datasetId: String,
    val uid: String,
)

/**
 * 按部署、dataset 与已认证 uid 隔离的固定紧急致命标记持久化。
 *
 * 仅用 [dataDir] 创建的 dumper 刻意是未拥有状态的。该命名空间绝不会被已认证 uploader 扫描，
 * 因此登录前崩溃无法归因于下一个登录的账号。已认证会话使用带 owner 的构造器，并且只能看到其
 * 精确的规范 TCP+HTTP 部署 + dataset + uid 命名空间。服务器重建绝不能从已退役 dataset 捕获的
 * 待处理诊断在替代 dataset 的 bearer 下被上传。
 */
internal class CrashDumper private constructor(
    dataDir: File,
    private val owner: CrashOwnerIdentity?,
) {
    constructor(dataDir: File) : this(dataDir, null)

    constructor(
        dataDir: File,
        deploymentIdentity: DeploymentIdentity,
        datasetId: String,
        ownerUid: String,
    ) : this(
        dataDir = dataDir,
        owner = crashOwnerIdentity(deploymentIdentity, datasetId, ownerUid),
    )

    private val pendingStore = privateAtomicTextFileStore(
        dataDir = dataDir,
        privateDirectories = owner?.let { identity ->
            listOf(
                CRASH_ROOT,
                stableCrashNamespace(identity.deploymentFingerprint),
                stableCrashNamespace(identity.datasetId),
                stableCrashNamespace(identity.uid),
            )
        } ?: listOf(CRASH_ROOT, UNOWNED_NAMESPACE),
        fileName = PENDING_FILE,
    )

    /** 该精确身份是否有待处理的崩溃。 */
    fun hasPending(): Boolean = synchronized(this) {
        runCatching(pendingStore::existsNonEmpty).getOrDefault(false)
    }

    /** 固定 owner 的 uploader 输入；从构造上就不可能读取其他命名空间。 */
    internal fun pendingContent(): String? = synchronized(this) {
        runCatching(pendingStore::readText).getOrNull()?.takeIf(String::isNotEmpty)
    }

    /** 只删除已上传的精确载荷；在途写入的较新崩溃必须存活。 */
    internal fun markPendingUploaded(expectedContent: String) = synchronized(this) {
        if (runCatching(pendingStore::readText).getOrNull() == expectedContent) {
            runCatching(pendingStore::delete)
        }
    }

    /** 原子的 best-effort 持久化。失败绝不会掩盖原始崩溃。 */
    fun flushPending(content: String) {
        synchronized(this) {
            try {
                pendingStore.replaceText(content)
            } catch (_: Throwable) {
                // 崩溃持久化本身绝不能成为第二个致命错误。
            }
        }
    }

    private companion object {
        const val CRASH_ROOT = "pending-crashes"
        const val UNOWNED_NAMESPACE = "unowned"
        const val PENDING_FILE = "pending-crash.log"
    }
}

private fun crashOwnerIdentity(
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    ownerUid: String,
): CrashOwnerIdentity {
    com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
    require(ownerUid.isNotBlank()) { "Crash owner uid must not be blank" }
    return CrashOwnerIdentity(
        deploymentFingerprint = deploymentIdentity.fingerprint,
        datasetId = datasetId,
        uid = ownerUid,
    )
}

/** 稳定、路径安全的双通道哈希；原始服务器坐标与 uid 绝不会成为路径组件。 */
private fun stableCrashNamespace(value: String): String {
    var first = 1_125_899_906_842_597L
    var second = -7_046_029_254_386_353_131L
    value.forEach { char ->
        first = first * 31L + char.code
        second = (second xor char.code.toLong()) * 1_099_511_628_211L
    }
    return "${value.length}-${first.toString(36)}-${second.toString(36)}"
}

/** 进程未捕获异常处理器的入口；原始崩溃文本被刻意忽略。 */
fun flushPendingCrash(dataDir: File, @Suppress("UNUSED_PARAMETER") content: String) {
    val fixedOwner = AppLog.ownerSnapshot()
    if (fixedOwner?.flushCrash(dataDir, CLIENT_TELEMETRY_FATAL_MARKER) != true) {
        CrashDumper(dataDir).flushPending(CLIENT_TELEMETRY_FATAL_MARKER)
    }
}
