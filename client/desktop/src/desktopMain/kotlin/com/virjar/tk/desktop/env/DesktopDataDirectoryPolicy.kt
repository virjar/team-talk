package com.virjar.tk.desktop.env

import com.virjar.tk.app.identity.ClientIdentity
import com.virjar.tk.shared.client.JvmMacOsAcl
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.shared.client.JvmFileSystemIdentity
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/** 输入被显式化，这样平台选择可以在不伪装成其他操作系统的前提下被测试。 */
internal data class DesktopDataDirectoryInputs(
    val osName: String,
    val userHome: File,
    val environment: Map<String, String>,
    val explicitDataDirectory: String?,
    val dataDirectoryName: String = ClientIdentity.DESKTOP_DATA_DIRECTORY_NAME,
    val linuxDataDirectoryName: String = ClientIdentity.LINUX_DATA_DIRECTORY_NAME,
)

internal data class DesktopDataDirectoryPlan(
    val dataDirectory: File,
    val currentUserAnchor: File,
    val ownerAnchor: File,
    val baseDirectory: File,
    val isExplicitOverride: Boolean,
)

internal object DesktopDataDirectoryPolicy {
    fun currentInputs(): DesktopDataDirectoryInputs = DesktopDataDirectoryInputs(
        osName = System.getProperty("os.name").orEmpty(),
        userHome = File(requireNotNull(System.getProperty("user.home")) { "user.home is not set" }),
        environment = System.getenv(),
        explicitDataDirectory = System.getProperty(DATA_DIRECTORY_PROPERTY),
    )

    fun resolve(inputs: DesktopDataDirectoryInputs): DesktopDataDirectoryPlan {
        val home = inputs.userHome.toPath().toAbsolutePath().normalize()
        require(home.parent != null) { "Desktop user home cannot be a filesystem root" }

        inputs.explicitDataDirectory?.let { raw ->
            require(raw.isNotBlank()) { "$DATA_DIRECTORY_PROPERTY cannot be blank" }
            val explicit = Path.of(raw)
            require(explicit.isAbsolute) { "$DATA_DIRECTORY_PROPERTY must be an absolute path" }
            val normalized = explicit.normalize()
            require(normalized.parent != null && normalized != home) {
                "$DATA_DIRECTORY_PROPERTY must name a dedicated child directory"
            }
            return DesktopDataDirectoryPlan(
                dataDirectory = normalized.toFile(),
                currentUserAnchor = home.toFile(),
                ownerAnchor = home.toFile(),
                baseDirectory = requireNotNull(normalized.parent).toFile(),
                isExplicitOverride = true,
            )
        }

        val base = when (desktopHostPlatform(inputs.osName)) {
            DesktopHostPlatform.MACOS -> home.resolve("Library").resolve("Application Support")
            DesktopHostPlatform.WINDOWS -> inputs.environment["LOCALAPPDATA"]
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf { it.isAbsolute }
                ?.normalize()
                ?: home.resolve("AppData").resolve("Local")
            DesktopHostPlatform.LINUX -> inputs.environment["XDG_DATA_HOME"]
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf { it.isAbsolute }
                ?.normalize()
                ?: home.resolve(".local").resolve("share")
        }
        val dataDirectory = base.resolve(
            if (desktopHostPlatform(inputs.osName) == DesktopHostPlatform.LINUX) {
                inputs.linuxDataDirectoryName
            } else {
                inputs.dataDirectoryName
            },
        ).normalize()
        return DesktopDataDirectoryPlan(
            dataDirectory = dataDirectory.toFile(),
            currentUserAnchor = home.toFile(),
            ownerAnchor = home.toFile(),
            baseDirectory = base.toFile(),
            isExplicitOverride = false,
        )
    }

    /** 校验从根到 base 的整条链；只在安全的用户自有 home 链中创建缺失的标准父目录。 */
    fun prepareBaseDirectory(plan: DesktopDataDirectoryPlan) {
        val currentUserAnchor = plan.currentUserAnchor.toPath().toAbsolutePath().normalize()
        requireRealDirectory(attributes(currentUserAnchor), "Desktop user home")
        val expectedOwner = JvmFileSystemIdentity.currentOwner(currentUserAnchor)
        val base = plan.baseDirectory.toPath().toAbsolutePath().normalize()
        val trustedOwners = JvmFileSystemIdentity.trustedParentOwners(base, expectedOwner)

        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            require(!plan.isExplicitOverride && base.startsWith(currentUserAnchor)) {
                "Only standard Desktop app-data parents below user.home may be created"
            }
        }

        val filesystemRoot = requireNotNull(base.root) { "Desktop app-data parent has no filesystem root" }
        var current = filesystemRoot
        validateStableParent(current, expectedOwner, trustedOwners, "Desktop app-data parent chain")
        for (component in filesystemRoot.relativize(base)) {
            val child = current.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                require(
                    !plan.isExplicitOverride && child.startsWith(currentUserAnchor) &&
                        Files.getOwner(current, LinkOption.NOFOLLOW_LINKS) in trustedOwners,
                ) { "Missing Desktop app-data parents may only be created in a user-owned home chain" }
                createSafeStandardParent(child, expectedOwner, trustedOwners)
            } else {
                validateStableParent(child, expectedOwner, trustedOwners, "Desktop app-data parent chain")
            }
            current = child
        }
    }

    /** 兼容旧启动器按 umask 创建的 0755 根；只撤销额外读取/遍历权限，不接管可被他人写入的目录。 */
    fun tightenExistingRootPermissions(plan: DesktopDataDirectoryPlan) {
        val root = plan.dataDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        val before = attributes(root)
        requireRealDirectory(before, "Desktop app-data directory")
        val expectedOwner = JvmFileSystemIdentity.currentOwner(plan.currentUserAnchor.toPath())
        require(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS) == expectedOwner) {
            "Desktop app-data directory has the wrong owner"
        }
        val posix = Files.getFileAttributeView(root, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return // Windows 保留原有精确 ACL 校验，不自动重写 ACL。
        val permissions = posix.readAttributes().permissions()
        if (permissions == PRIVATE_STANDARD_PARENT_PERMISSIONS) return
        require(permissions.containsAll(PRIVATE_STANDARD_PARENT_PERMISSIONS) && permissions.none {
            it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.OTHERS_WRITE
        }) { "Existing Desktop app-data permissions cannot be safely tightened to 0700" }
        // chmod 不能消除扩展 ACL 的授权；先拒绝不安全 ACL，不清除用户已有 ACL。
        JvmMacOsAcl.requirePrivateLeaf(root)
        require(before.fileKey() != null) { "Desktop app-data directory has no stable identity" }
        posix.setPermissions(PRIVATE_STANDARD_PARENT_PERMISSIONS)
        val after = attributes(root)
        require(before.fileKey() == after.fileKey() && !after.isSymbolicLink && after.isDirectory) {
            "Desktop app-data directory changed while tightening permissions"
        }
        require(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS) == expectedOwner &&
            posix.readAttributes().permissions() == PRIVATE_STANDARD_PARENT_PERMISSIONS) {
            "Desktop app-data directory permission repair did not preserve its owner"
        }
    }

    private fun validateStableParent(
        path: Path,
        expectedOwner: UserPrincipal,
        trustedOwners: Set<UserPrincipal>,
        label: String,
    ) {
        requireRealDirectory(attributes(path), label)
        require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) in trustedOwners) {
            "$label has an untrusted owner"
        }
        val posix = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (posix != null) {
            require(posix.readAttributes().permissions().none {
                it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.OTHERS_WRITE
            }) { "$label cannot be writable by group or others" }
            JvmMacOsAcl.requireSafeParent(path)
            return
        }
        JvmFileSystemIdentity.requireSafeWindowsParent(path, expectedOwner, trustedOwners)
    }

    private fun createSafeStandardParent(
        path: Path,
        expectedOwner: UserPrincipal,
        trustedOwners: Set<UserPrincipal>,
    ) {
        val parent = requireNotNull(path.parent)
        validateStableParent(parent, expectedOwner, trustedOwners, "Desktop app-data parent chain")
        require(Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS) in trustedOwners) {
            "Desktop app-data parent creation requires a current-user-owned parent"
        }
        val posix = Files.getFileAttributeView(
            parent,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        var created = false
        try {
            if (posix != null) {
                Files.createDirectory(path, PRIVATE_STANDARD_PARENT_ATTRIBUTE)
                created = true
                JvmMacOsAcl.clearNewPathAcl(path)
                Files.setPosixFilePermissions(path, PRIVATE_STANDARD_PARENT_PERMISSIONS)
            } else {
                Files.createDirectory(path)
                created = true
                Files.setOwner(path, expectedOwner)
            }
            require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == expectedOwner) {
                "New Desktop app-data parent has the wrong owner"
            }
            validateStableParent(path, expectedOwner, trustedOwners, "Desktop app-data parent chain")
        } catch (failure: Throwable) {
            var terminalFailure = failure
            if (created) {
                try {
                    Files.deleteIfExists(path)
                } catch (cleanupFailure: Throwable) {
                    terminalFailure = mergeDesktopPathFailures(terminalFailure, cleanupFailure)
                }
            }
            throw terminalFailure
        }
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun requireRealDirectory(attributes: BasicFileAttributes, label: String) {
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            "$label must be a real directory"
        }
    }

    private const val DATA_DIRECTORY_PROPERTY = "teamtalk.data.dir"
    private val PRIVATE_STANDARD_PARENT_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
    private val PRIVATE_STANDARD_PARENT_ATTRIBUTE =
        PosixFilePermissions.asFileAttribute(PRIVATE_STANDARD_PARENT_PERMISSIONS)

}

private fun mergeDesktopPathFailures(primary: Throwable, additional: Throwable): Throwable {
    if (primary === additional) return primary
    val primaryFatal = primary is CancellationException || primary !is Exception
    val additionalFatal = additional is CancellationException || additional !is Exception
    return if (!primaryFatal && additionalFatal) {
        additional.addSuppressed(primary)
        additional
    } else {
        primary.addSuppressed(additional)
        primary
    }
}

internal enum class DesktopHostPlatform {
    MACOS,
    WINDOWS,
    LINUX,
}

internal fun desktopHostPlatform(osName: String): DesktopHostPlatform = when {
    osName.startsWith("Windows", ignoreCase = true) -> DesktopHostPlatform.WINDOWS
    osName.startsWith("Mac", ignoreCase = true) || osName.contains("Darwin", ignoreCase = true) ->
        DesktopHostPlatform.MACOS
    else -> DesktopHostPlatform.LINUX
}

/** 在日志、锁、崩溃持久化或认证初始化之前解析一次。 */
internal object DesktopEnvironment {
    fun prepareDataDirectory(): File = DesktopDataDirectoryAdmission.prepare(
        DesktopDataDirectoryPolicy.resolve(DesktopDataDirectoryPolicy.currentInputs()),
    )
}

/** 只打开当前用户数据根；旧 MSIX 覆盖层只读检查，不认领或合并历史目录。 */
internal object DesktopDataDirectoryAdmission {
    fun prepare(plan: DesktopDataDirectoryPlan): File {
        WindowsMsixDataDirectory.checkBeforeOpening(plan)
        DesktopDataDirectoryPolicy.prepareBaseDirectory(plan)
        DesktopDataDirectoryPolicy.tightenExistingRootPermissions(plan)
        val data = JvmPrivateDataDirectory.openOrCreate(plan.dataDirectory, plan.ownerAnchor)
        val marker = data.atomicTextFile(fileName = DATA_MARKER_FILE)
        val existing = marker.readText(MAX_MARKER_BYTES)
        if (existing == null) {
            val directoryIsEmpty = Files.newDirectoryStream(data.root).use { children ->
                !children.iterator().hasNext()
            }
            require(directoryIsEmpty) {
                "Existing Desktop app-data directory is unmarked and non-empty; refusing to adopt it"
            }
            marker.replaceText(DATA_MARKER_CONTENT)
        } else {
            require(existing == DATA_MARKER_CONTENT) { "Unknown Desktop app-data marker" }
        }
        return data.root.toFile()
    }

    private const val DATA_MARKER_FILE = ".teamtalk-desktop-data"
    private const val DATA_MARKER_CONTENT = "teamtalk-desktop-data-v1\n"
    private const val MAX_MARKER_BYTES = 256L
}
