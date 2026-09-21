package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.platform.PlatformFile as File

/** 封禁清理的不可变账号归属；不携带凭据，不从用户名或当前登录状态猜测。 */
data class AccountDataOwner(
    val deploymentFingerprint: String,
    val datasetId: String,
    val uid: String,
) {
    init {
        validatedDeploymentFingerprint(deploymentFingerprint)
        validatedLocalCacheDatasetId(datasetId)
        validatedLocalCacheOwnerId(uid)
    }
}

/** 一个精确子树，或一个目录下按完整文件名匹配的直属子项；绝不删除受信任根本身。 */
class AccountDataCleanupTarget private constructor(
    private val root: File,
    private val components: List<String>,
    private val childNames: Regex?,
) {
    init {
        require(components.isNotEmpty() || childNames != null) { "Cannot delete an installation root" }
        components.forEach { component ->
            require(component.isNotBlank() && component != "." && component != ".." &&
                component.none { it == '/' || it == '\\' || it.isISOControl() }) {
                "Unsafe account cleanup path component"
            }
        }
    }

    internal fun delete() {
        var target = root.absoluteFile
        if (!target.exists()) return
        require(target.isDirectory && !target.isSymbolicLink()) { "Account cleanup root is not a directory" }
        components.forEachIndexed { index, component ->
            target = File(target, component)
            if (!target.exists()) return
            if (index < components.lastIndex || childNames != null) {
                require(target.isDirectory && !target.isSymbolicLink()) { "Account cleanup ancestor is not a directory" }
            }
        }
        if (childNames == null) deleteAccountTree(target)
        else checkNotNull(target.listFiles()) { "Cannot read account cleanup directory" }
            .filter { childNames.matches(it.name) }.forEach(::deleteAccountTree)
    }

    companion object {
        fun tree(root: File, vararg components: String): AccountDataCleanupTarget =
            AccountDataCleanupTarget(root, components.toList(), null)

        fun matchingChildren(root: File, components: List<String>, names: Regex): AccountDataCleanupTarget =
            AccountDataCleanupTarget(root, components, names)
    }
}

/**
 * 封禁清理的磁盘日志。调用顺序：begin → 退役全部账号资源 → deleteOwnedData → 清凭据 → complete。
 *
 * begin 必须早于清除任何身份信息；清理失败或进程中断会留下不含秘密的 owner 标记。平台在打开凭据、
 * 数据库或草稿之前重放 pendingOwners。完成标记只在数据和凭据都已清除之后移除。
 * 这是阻塞式 IO 边界；运行时由组合根调度到 IO 线程，并在整个期间禁止重新进入旧工作区。
 */
class AccountDataCleanup(
    private val markerDataDir: File,
    private val targets: (AccountDataOwner) -> List<AccountDataCleanupTarget>,
) {
    private val methodLock = PlatformLock()
    fun begin(owner: AccountDataOwner): Unit = synchronized(methodLock) {
        marker(owner).replaceText(encode(owner), MAX_MARKER_BYTES)
    }

    fun pendingOwners(): List<AccountDataOwner> = synchronized(methodLock) {
        val root = markerDataDir.absoluteFile
        require(root.isDirectory && !root.isSymbolicLink()) { "Account cleanup data root is not a directory" }
        val directory = File(root, MARKER_DIRECTORY)
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory && !directory.isSymbolicLink()) { "Account cleanup marker directory is not a directory" }
        return checkNotNull(directory.listFiles()) { "Cannot read account cleanup markers" }
            .filter { it.name.endsWith(MARKER_SUFFIX) }.map { path ->
                val name = path.name
                require(MARKER_NAME.matches(name)) { "Invalid account cleanup marker name" }
                val content = checkNotNull(markerNamed(name).readText(MAX_MARKER_BYTES)) {
                    "Account cleanup marker disappeared"
                }
                val owner = decode(content)
                require(markerName(owner) == name) { "Account cleanup marker owner does not match its name" }
                owner
            }.sortedWith(compareBy({ it.deploymentFingerprint }, { it.datasetId }, { it.uid }))
    }

    fun deleteOwnedData(owner: AccountDataOwner): Unit = synchronized(methodLock) {
        check(marker(owner).readText(MAX_MARKER_BYTES) == encode(owner)) {
            "Account data must be marked before deletion"
        }
        targets(owner).forEach { it.delete() }
    }

    /** 调用方已确认账号资源、持久内容和相同 scope 的凭据清理成功；不可放在 finally 中调用。 */
    fun complete(owner: AccountDataOwner): Unit = synchronized(methodLock) {
        marker(owner).delete()
    }

    private fun marker(owner: AccountDataOwner) = markerNamed(markerName(owner))
    private fun markerNamed(name: String) = privateAtomicTextFileStore(
        dataDir = markerDataDir,
        privateDirectories = listOf(MARKER_DIRECTORY),
        fileName = name,
    )

    private fun markerName(owner: AccountDataOwner): String =
        platformSha256Hex(encode(owner).encodeToByteArray()) + MARKER_SUFFIX

    private fun encode(owner: AccountDataOwner): String =
        "account-ban-v1\n${owner.deploymentFingerprint}\n${owner.datasetId}\n${owner.uid}\n"

    private fun decode(content: String): AccountDataOwner {
        val lines = content.split('\n')
        require(lines.size == 5 && lines[0] == "account-ban-v1" && lines[4].isEmpty()) {
            "Invalid account cleanup marker; account data was retained"
        }
        return AccountDataOwner(lines[1], lines[2], lines[3])
    }

    private companion object {
        const val MARKER_DIRECTORY = ".account-cleanup"
        const val MARKER_SUFFIX = ".pending"
        const val MAX_MARKER_BYTES = 1024L
        val MARKER_NAME = Regex("[0-9a-f]{64}\\.pending")
    }
}

/** JVM 和 Android 账号遥测采用相同命名；不触碰未归属诊断与其他账号日志。 */
fun accountDiagnosticCleanupTargets(dataDir: File, owner: AccountDataOwner): List<AccountDataCleanupTarget> {
    val identity = arrayOf(
        stableTelemetryNamespace(owner.deploymentFingerprint),
        stableTelemetryNamespace(owner.datasetId),
        stableTelemetryNamespace(owner.uid),
    )
    return listOf(
        AccountDataCleanupTarget.tree(dataDir, CLIENT_TELEMETRY_ROOT_DIRECTORY, *identity),
        AccountDataCleanupTarget.tree(dataDir, "pending-crashes", *identity),
    )
}

/** Android 账号数据库文件族；databases/ 含 sidecar/恢复副本，cache/ 另含同名 .db.lck。 */
fun accountAndroidDatabaseCleanupTarget(databaseDirectory: File, owner: AccountDataOwner): AccountDataCleanupTarget {
    val identity = "_${Regex.escape(owner.deploymentFingerprint)}_${Regex.escape(owner.datasetId)}_${Regex.escape(owner.uid)}"
    val lifecycleSuffix = "(?:-wal|-shm|-journal|\\.lck|\\.corruption-reported|\\.integrity-checked|\\.open)?"
    return AccountDataCleanupTarget.matchingChildren(
        databaseDirectory,
        emptyList(),
        Regex("cache_e[0-9]+$identity\\.db(?:\\.corrupt-[A-Za-z0-9-]+)?$lifecycleSuffix"),
    )
}

private fun deleteAccountTree(root: File) {
    if (root.isDirectory && !root.isSymbolicLink()) {
        checkNotNull(root.listFiles()) { "Cannot read account data directory" }.forEach(::deleteAccountTree)
    }
    check(root.delete()) { "Cannot delete account data entry" }
}
