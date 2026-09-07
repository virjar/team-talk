package com.virjar.tk.shared.client

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

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
        var target = root.toPath().toAbsolutePath().normalize()
        if (!Files.exists(target, NOFOLLOW_LINKS)) return
        require(Files.isDirectory(target, NOFOLLOW_LINKS)) { "Account cleanup root is not a directory" }
        components.forEachIndexed { index, component ->
            target = target.resolve(component)
            if (!Files.exists(target, NOFOLLOW_LINKS)) return
            // A leaf link may itself be removed. Ancestor links must never be traversed.
            if (index < components.lastIndex || childNames != null) {
                require(Files.isDirectory(target, NOFOLLOW_LINKS)) {
                    "Account cleanup ancestor is not a directory"
                }
            }
        }
        if (childNames == null) {
            deleteAccountTree(target)
        } else {
            Files.newDirectoryStream(target).use { entries ->
                entries.filter { childNames.matches(it.fileName.toString()) }.forEach(::deleteAccountTree)
            }
        }
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
    @Synchronized
    fun begin(owner: AccountDataOwner) {
        marker(owner).replaceText(encode(owner), MAX_MARKER_BYTES)
    }

    @Synchronized
    fun pendingOwners(): List<AccountDataOwner> {
        val root = markerDataDir.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Account cleanup data root is not a directory" }
        val directory = root.resolve(MARKER_DIRECTORY)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Account cleanup marker directory is not a directory" }
        return Files.newDirectoryStream(directory).use { entries ->
            entries.filter { it.fileName.toString().endsWith(MARKER_SUFFIX) }.map { path ->
                val name = path.fileName.toString()
                require(MARKER_NAME.matches(name)) { "Invalid account cleanup marker name" }
                val content = checkNotNull(markerNamed(name).readText(MAX_MARKER_BYTES)) {
                    "Account cleanup marker disappeared"
                }
                val owner = decode(content)
                require(markerName(owner) == name) { "Account cleanup marker owner does not match its name" }
                owner
            }.sortedWith(compareBy({ it.deploymentFingerprint }, { it.datasetId }, { it.uid }))
        }
    }

    @Synchronized
    fun deleteOwnedData(owner: AccountDataOwner) {
        check(marker(owner).readText(MAX_MARKER_BYTES) == encode(owner)) {
            "Account data must be marked before deletion"
        }
        targets(owner).forEach { it.delete() }
    }

    /** 调用方已确认账号资源、持久内容和相同 scope 的凭据清理成功；不可放在 finally 中调用。 */
    @Synchronized
    fun complete(owner: AccountDataOwner) {
        marker(owner).delete()
    }

    private fun marker(owner: AccountDataOwner) = markerNamed(markerName(owner))
    private fun markerNamed(name: String) = privateAtomicTextFileStore(
        dataDir = markerDataDir,
        privateDirectories = listOf(MARKER_DIRECTORY),
        fileName = name,
    )

    private fun markerName(owner: AccountDataOwner): String =
        MessageDigest.getInstance("SHA-256").digest(encode(owner).encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) } + MARKER_SUFFIX

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

/** Android 数据库在 databases/ 中按账号命名，含 SQLite sidecar、恢复标记和损坏隔离副本。 */
fun accountAndroidDatabaseCleanupTarget(databaseDirectory: File, owner: AccountDataOwner): AccountDataCleanupTarget {
    val identity = "_${Regex.escape(owner.deploymentFingerprint)}_${Regex.escape(owner.datasetId)}_${Regex.escape(owner.uid)}"
    val lifecycleSuffix = "(?:-wal|-shm|-journal|\\.corruption-reported|\\.integrity-checked|\\.open)?"
    return AccountDataCleanupTarget.matchingChildren(
        databaseDirectory,
        emptyList(),
        Regex("cache_e[0-9]+$identity\\.db(?:\\.corrupt-[A-Za-z0-9-]+)?$lifecycleSuffix"),
    )
}

private fun deleteAccountTree(root: Path) {
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
