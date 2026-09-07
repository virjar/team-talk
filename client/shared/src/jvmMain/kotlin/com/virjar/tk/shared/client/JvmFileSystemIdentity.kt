package com.virjar.tk.shared.client

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Secur32
import com.sun.jna.platform.win32.Secur32Util
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.UserPrincipal

/** Desktop 与无头 SDK 共用身份来源；Windows profile 的 owner 不一定是登录用户。 */
object JvmFileSystemIdentity {
    fun currentOwner(anchor: Path): UserPrincipal = if (hasPosixPermissions(anchor)) {
        Files.getOwner(anchor, LinkOption.NOFOLLOW_LINKS)
    } else {
        // 从 Windows 当前执行身份取得域限定名，不读取可覆盖的 user.name / USERDOMAIN，也不写探测文件。
        val name = Secur32Util.getUserNameEx(Secur32.EXTENDED_NAME_FORMAT.NameSamCompatible)
        anchor.fileSystem.userPrincipalLookupService.lookupPrincipalByName(name)
    }

    fun trustedParentOwners(path: Path, owner: UserPrincipal): Set<UserPrincipal> = buildSet {
        add(owner)
        add(Files.getOwner(requireNotNull(path.root), LinkOption.NOFOLLOW_LINKS))
        if (!hasPosixPermissions(path.root)) {
            val lookup = path.fileSystem.userPrincipalLookupService
            windowsSystemAccountNames.forEach { add(lookup.lookupPrincipalByName(it)) }
        }
    }

    fun requireSafeWindowsParent(path: Path, owner: UserPrincipal, trustedOwners: Set<UserPrincipal>) {
        require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) in trustedOwners) {
            "Private data parent has an untrusted Windows owner: $path"
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: error("Windows private storage requires an ACL view: $path")
        require(WindowsSafeParentAclPolicy.isSafe(owner, trustedOwners, acl.acl)) {
            "Private data parent grants mutation rights to another Windows principal: $path"
        }
    }

    private fun hasPosixPermissions(path: Path): Boolean =
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null

    // SID 先通过 Windows 解析成本地化账户名，再交给 NIO；lookupPrincipalByName 不接受 SID 字符串。
    // SYSTEM / Administrators 属于机器管理边界，Authenticated Users 和 Everyone 不在此集合中。
    private val windowsSystemAccountNames: List<String> by lazy {
        listOf("S-1-5-18", "S-1-5-32-544").map { Advapi32Util.getAccountBySid(it).fqn }
    }
}

/** 父链逐目录检查；子项创建权不等于替换现有目录，继承专用 ACE 也不作用于本目录。 */
object WindowsSafeParentAclPolicy {
    private val mutationPermissions = setOf(
        AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER,
    )

    fun isSafe(owner: UserPrincipal, trustedSystemPrincipals: Set<UserPrincipal>, entries: List<AclEntry>): Boolean =
        entries.none { entry ->
            entry.type() == AclEntryType.ALLOW &&
                AclEntryFlag.INHERIT_ONLY !in entry.flags() &&
                entry.principal() != owner && entry.principal() !in trustedSystemPrincipals &&
                entry.permissions().any { it in mutationPermissions }
        }
}
