package com.virjar.tk.shared.client

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

internal enum class JvmPrivateAccessKind {
    POSIX,
    WINDOWS_ACL,
}

internal fun selectJvmPrivateAccessKind(posixAvailable: Boolean, windowsAclAvailable: Boolean): JvmPrivateAccessKind =
    when {
        posixAvailable -> JvmPrivateAccessKind.POSIX
        windowsAclAvailable -> JvmPrivateAccessKind.WINDOWS_ACL
        else -> error("Private storage requires POSIX permissions or Windows ACL support")
    }

/** 非 Windows CI 上的 Windows 测试使用的纯校验接缝。 */
internal object WindowsOwnerOnlyAclPolicy {
    val requiredPermissions: Set<AclEntryPermission> = EnumSet.allOf(AclEntryPermission::class.java)
    private val directoryInheritanceFlags: Set<AclEntryFlag> = EnumSet.of(
        AclEntryFlag.DIRECTORY_INHERIT,
        AclEntryFlag.FILE_INHERIT,
    )

    fun ownerOnlyAcl(owner: UserPrincipal, directory: Boolean): List<AclEntry> = listOf(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(requiredPermissions)
            .setFlags(if (directory) directoryInheritanceFlags else emptySet())
            .build(),
    )

    fun isOwnerOnly(owner: UserPrincipal, entries: List<AclEntry>, directory: Boolean): Boolean =
        entries.size == 1 && entries.single().let { entry ->
            entry.type() == AclEntryType.ALLOW &&
                entry.principal() == owner &&
                entry.permissions() == requiredPermissions &&
                entry.flags() == if (directory) directoryInheritanceFlags else emptySet<AclEntryFlag>()
        }
}

internal class JvmPrivatePathSecurity private constructor(
    private val kind: JvmPrivateAccessKind,
    private val expectedOwner: UserPrincipal,
) {
    fun createDirectory(path: Path) {
        var created = false
        try {
            when (kind) {
                JvmPrivateAccessKind.POSIX -> {
                    Files.createDirectory(path, PRIVATE_DIRECTORY_ATTRIBUTE)
                    created = true
                    JvmMacOsAcl.clearNewPathAcl(path)
                    Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY_PERMISSIONS)
                }
                JvmPrivateAccessKind.WINDOWS_ACL -> {
                    Files.createDirectory(path, windowsAclAttribute(directory = true))
                    created = true
                    normalizeNewWindowsAcl(path, directory = true)
                }
            }
            requirePrivateDirectory(path)
        } catch (failure: Throwable) {
            var terminalFailure = failure
            if (created) {
                try {
                    Files.deleteIfExists(path)
                } catch (cleanupFailure: Throwable) {
                    terminalFailure = mergeSessionLifecycleFailures(terminalFailure, cleanupFailure)
                }
            }
            throw terminalFailure
        }
    }

    fun createEmptyFile(path: Path) {
        var created = false
        try {
            when (kind) {
                JvmPrivateAccessKind.POSIX -> {
                    Files.createFile(path, PRIVATE_FILE_ATTRIBUTE)
                    created = true
                    JvmMacOsAcl.clearNewPathAcl(path)
                    Files.setPosixFilePermissions(path, PRIVATE_FILE_PERMISSIONS)
                }
                JvmPrivateAccessKind.WINDOWS_ACL -> {
                    Files.createFile(path, windowsAclAttribute(directory = false))
                    created = true
                    normalizeNewWindowsAcl(path, directory = false)
                }
            }
            useResourcePreservingFatalFailure(FileChannel.open(path, PRIVATE_FILE_SYNC_OPTIONS)) { channel ->
                channel.force(true)
            }
            requirePrivateFile(path)
        } catch (failure: Throwable) {
            var terminalFailure = failure
            if (created) {
                try {
                    Files.deleteIfExists(path)
                } catch (cleanupFailure: Throwable) {
                    terminalFailure = mergeSessionLifecycleFailures(terminalFailure, cleanupFailure)
                }
            }
            throw terminalFailure
        }
    }

    fun createTempFile(directory: Path, prefix: String, suffix: String): Path {
        var path: Path? = null
        try {
            val created = when (kind) {
                JvmPrivateAccessKind.POSIX ->
                    Files.createTempFile(directory, prefix, suffix, PRIVATE_FILE_ATTRIBUTE).also { created ->
                        path = created
                        JvmMacOsAcl.clearNewPathAcl(created)
                        Files.setPosixFilePermissions(created, PRIVATE_FILE_PERMISSIONS)
                    }
                JvmPrivateAccessKind.WINDOWS_ACL ->
                    Files.createTempFile(
                        directory,
                        prefix,
                        suffix,
                        windowsAclAttribute(directory = false),
                    ).also { created ->
                        path = created
                        normalizeNewWindowsAcl(created, directory = false)
                    }
            }
            path = created
            requirePrivateFile(created)
            return created
        } catch (failure: Throwable) {
            var terminalFailure = failure
            path?.let { created ->
                try {
                    Files.deleteIfExists(created)
                } catch (cleanupFailure: Throwable) {
                    terminalFailure = mergeSessionLifecycleFailures(terminalFailure, cleanupFailure)
                }
            }
            throw terminalFailure
        }
    }

    fun requirePrivateDirectory(path: Path): BasicFileAttributes {
        val attributes = basicAttributes(path)
        requireRealDirectory(attributes, "Private directory")
        requireSameOwner(path, expectedOwner, "Private directory")
        when (kind) {
            JvmPrivateAccessKind.POSIX -> {
                require(posixPermissions(path) == PRIVATE_DIRECTORY_PERMISSIONS) {
                    "Existing private directory permissions are not 0700"
                }
                JvmMacOsAcl.requirePrivateLeaf(path)
            }
            JvmPrivateAccessKind.WINDOWS_ACL -> requireWindowsOwnerOnlyAcl(path, directory = true)
        }
        return attributes
    }

    fun requirePrivateFile(path: Path): BasicFileAttributes {
        val attributes = basicAttributes(path)
        requireRealFile(attributes, "Private file")
        requireSameOwner(path, expectedOwner, "Private file")
        when (kind) {
            JvmPrivateAccessKind.POSIX -> {
                require(posixPermissions(path) == PRIVATE_FILE_PERMISSIONS) {
                    "Existing private file permissions are not 0600"
                }
                require(unixLinkCount(path) == 1) { "Hard-linked private files are not allowed" }
                JvmMacOsAcl.requirePrivateLeaf(path)
            }
            JvmPrivateAccessKind.WINDOWS_ACL -> {
                // 精确 DACL 使包含树对其他用户不可访问。原子替换同样意味着同 owner 硬链接绝不被
                // 就地修改。
                requireWindowsOwnerOnlyAcl(path, directory = false)
            }
        }
        return attributes
    }

    fun forceDirectory(directory: Path) {
        if (kind == JvmPrivateAccessKind.POSIX) {
            useResourcePreservingFatalFailure(FileChannel.open(directory, DIRECTORY_FORCE_OPTIONS)) { channel ->
                channel.force(true)
            }
        }
    }

    fun forceDirectoryDurably(directory: Path, expectedStorageIdentity: Any) {
        if (kind != JvmPrivateAccessKind.POSIX) {
            throw UnsupportedOperationException(
                "Durable directory metadata is unavailable for the active private-storage provider",
            )
        }
        val before = requirePrivateDirectory(directory)
        require(before.fileKey() != null && before.fileKey() == expectedStorageIdentity) {
            "Private directory no longer matches its secure handle"
        }
        useResourcePreservingFatalFailure(FileChannel.open(directory, DIRECTORY_FORCE_OPTIONS)) { channel ->
            channel.force(true)
        }
        val after = requirePrivateDirectory(directory)
        require(after.fileKey() != null && after.fileKey() == expectedStorageIdentity) {
            "Private directory changed while forcing metadata"
        }
    }

    private fun requireWindowsOwnerOnlyAcl(path: Path, directory: Boolean) {
        val view = windowsAclView(path)
        require(view.owner == expectedOwner) { "Private path has the wrong Windows owner" }
        require(WindowsOwnerOnlyAclPolicy.isOwnerOnly(expectedOwner, view.acl, directory)) {
            "Existing private path does not have an owner-only Windows ACL"
        }
    }

    /** 新建路径可以被收紧；现有路径仅校验。 */
    private fun normalizeNewWindowsAcl(path: Path, directory: Boolean) {
        requireSameOwner(path, expectedOwner, "New private path")
        windowsAclView(path).acl = WindowsOwnerOnlyAclPolicy.ownerOnlyAcl(expectedOwner, directory)
    }

    private fun windowsAclView(path: Path): AclFileAttributeView =
        Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: error("Windows private ACL view is unavailable")

    private fun windowsAclAttribute(directory: Boolean): FileAttribute<List<AclEntry>> =
        object : FileAttribute<List<AclEntry>> {
            override fun name(): String = "acl:acl"

            override fun value(): List<AclEntry> = WindowsOwnerOnlyAclPolicy.ownerOnlyAcl(expectedOwner, directory)
        }

    private fun posixPermissions(path: Path): Set<PosixFilePermission> =
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()
            ?.permissions()
            ?: error("POSIX permission view is unavailable")

    private fun unixLinkCount(path: Path): Int =
        (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt()

    companion object {
        private val DIRECTORY_FORCE_OPTIONS: Set<OpenOption> =
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

        fun forPath(path: Path, expectedOwner: UserPrincipal): JvmPrivatePathSecurity {
            val kind = selectJvmPrivateAccessKind(
                posixAvailable = Files.getFileAttributeView(
                    path,
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ) != null,
                windowsAclAvailable = Files.getFileAttributeView(
                    path,
                    AclFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ) != null,
            )
            return JvmPrivatePathSecurity(kind, expectedOwner)
        }
    }
}

internal fun basicAttributes(path: Path): BasicFileAttributes = Files.readAttributes(
    path,
    BasicFileAttributes::class.java,
    LinkOption.NOFOLLOW_LINKS,
)

internal fun requireRealDirectory(attributes: BasicFileAttributes, label: String) {
    require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
        "$label must be a real directory"
    }
}

internal fun requireRealFile(attributes: BasicFileAttributes, label: String) {
    require(attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther) {
        "$label must be a real regular file"
    }
}

internal fun requireSameOwner(path: Path, expectedOwner: UserPrincipal, label: String) {
    require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == expectedOwner) {
        "$label has the wrong owner"
    }
}

/** NOFOLLOW owner 链；POSIX 父级绝不能被 group 或 other 用户替换。 */
internal fun requireSafeOwnerChain(anchor: Path, parent: Path, expectedOwner: UserPrincipal) {
    if (!parent.startsWith(anchor)) return
    fun validate(path: Path) {
        requireRealDirectory(basicAttributes(path), "Private data parent chain")
        requireSameOwner(path, expectedOwner, "Private data parent chain")
        val posix = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(
            posix == null || posix.readAttributes().permissions().none {
                it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.OTHERS_WRITE
            },
        ) { "Private data parent chain is writable by another user" }
        if (posix != null) JvmMacOsAcl.requireSafeParent(path)
    }

    var current = anchor
    validate(current)
    for (component in anchor.relativize(parent)) {
        current = current.resolve(component)
        validate(current)
    }
}

private val PRIVATE_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
private val PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
private val PRIVATE_DIRECTORY_ATTRIBUTE = PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS)
private val PRIVATE_FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS)
private val PRIVATE_FILE_SYNC_OPTIONS: Set<OpenOption> = setOf(
    StandardOpenOption.WRITE,
    LinkOption.NOFOLLOW_LINKS,
)
