package com.virjar.tk.desktop.env

import com.virjar.tk.shared.client.JvmMacOsAcl
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
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
            if (desktopHostPlatform(inputs.osName) == DesktopHostPlatform.LINUX) "teamtalk" else "TeamTalk",
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
        val expectedOwner = Files.getOwner(currentUserAnchor, LinkOption.NOFOLLOW_LINKS)
        val base = plan.baseDirectory.toPath().toAbsolutePath().normalize()
        val trustedOwners = trustedOwners(base, expectedOwner)

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
                        Files.getOwner(current, LinkOption.NOFOLLOW_LINKS) == expectedOwner,
                ) { "Missing Desktop app-data parents may only be created in a user-owned home chain" }
                createSafeStandardParent(child, expectedOwner, trustedOwners)
            } else {
                validateStableParent(child, expectedOwner, trustedOwners, "Desktop app-data parent chain")
            }
            current = child
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
        val acl = Files.getFileAttributeView(
            path,
            AclFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("$label requires POSIX permissions or Windows ACL support")
        require(
            WindowsSafeParentAclPolicy.isSafe(expectedOwner, trustedOwners, acl.acl),
        ) { "$label grants mutation rights to another Windows principal" }
    }

    private fun createSafeStandardParent(
        path: Path,
        expectedOwner: UserPrincipal,
        trustedOwners: Set<UserPrincipal>,
    ) {
        val parent = requireNotNull(path.parent)
        validateStableParent(parent, expectedOwner, trustedOwners, "Desktop app-data parent chain")
        require(Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS) == expectedOwner) {
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

    private fun trustedOwners(path: Path, expectedOwner: UserPrincipal): Set<UserPrincipal> {
        val filesystemRoot = requireNotNull(path.root) { "Desktop data path has no filesystem root" }
        val owners = mutableSetOf(expectedOwner, Files.getOwner(filesystemRoot, LinkOption.NOFOLLOW_LINKS))
        val posix = Files.getFileAttributeView(
            filesystemRoot,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (posix == null) {
            WINDOWS_TRUSTED_SYSTEM_SIDS.mapNotNullTo(owners) { sid ->
                try {
                    path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(sid)
                } catch (_: Exception) {
                    null
                }
            }
        }
        return owners
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
    private val WINDOWS_TRUSTED_SYSTEM_SIDS = listOf(
        "S-1-5-18", // Local System
        "S-1-5-32-544", // Built-in Administrators
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464", // TrustedInstaller
    )
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

/** 纯 Windows ACL 接缝，让 Linux/macOS CI 也能守护父目录策略。 */
internal object WindowsSafeParentAclPolicy {
    private val mutationPermissions = setOf(
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER,
    )

    fun isSafe(
        owner: UserPrincipal,
        trustedSystemPrincipals: Set<UserPrincipal>,
        entries: List<AclEntry>,
    ): Boolean = entries.none { entry ->
        val appliesToThisDirectory = AclEntryFlag.INHERIT_ONLY !in entry.flags()
        val appliesToCreatedChildren =
            AclEntryFlag.DIRECTORY_INHERIT in entry.flags() || AclEntryFlag.FILE_INHERIT in entry.flags()
        entry.type() == AclEntryType.ALLOW &&
            (appliesToThisDirectory || appliesToCreatedChildren) &&
            entry.principal() != owner &&
            entry.principal() !in trustedSystemPrincipals &&
            entry.permissions().any { it in mutationPermissions }
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

/** 只打开当前用户数据根；绝不探测与安装位置相关的历史根目录。 */
internal object DesktopDataDirectoryAdmission {
    fun prepare(plan: DesktopDataDirectoryPlan): File {
        DesktopDataDirectoryPolicy.prepareBaseDirectory(plan)
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
