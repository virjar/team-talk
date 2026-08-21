package com.virjar.tk.env

import com.virjar.tk.client.JvmMacOsAcl
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

/** Inputs are explicit so platform selection is testable without impersonating another OS. */
internal data class DesktopDataDirectoryInputs(
    val osName: String,
    val userHome: File,
    val environment: Map<String, String>,
    val explicitDataDirectory: String?,
    val isDevelopment: Boolean,
    val codeSource: File,
)

internal data class DesktopDataDirectoryPlan(
    val dataDirectory: File,
    val currentUserAnchor: File,
    val ownerAnchor: File,
    val baseDirectory: File,
    val legacyInstallationDataDirectory: File?,
    val isExplicitOverride: Boolean,
)

internal object DesktopDataDirectoryPolicy {
    fun currentInputs(): DesktopDataDirectoryInputs = DesktopDataDirectoryInputs(
        osName = System.getProperty("os.name").orEmpty(),
        userHome = File(requireNotNull(System.getProperty("user.home")) { "user.home is not set" }),
        environment = System.getenv(),
        explicitDataDirectory = System.getProperty(DATA_DIRECTORY_PROPERTY),
        isDevelopment = System.getProperty(DEVELOPMENT_PROPERTY).toBoolean(),
        codeSource = File(
            DesktopDataDirectoryPolicy::class.java.protectionDomain.codeSource.location.toURI(),
        ),
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
                legacyInstallationDataDirectory = null,
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
        val legacy = if (inputs.isDevelopment) {
            // Development uses the same user app-data policy. Repository data is only used through
            // an explicit -Dteamtalk.data.dir override and is never silently claimed or migrated.
            null
        } else {
            legacyInstallationDataDirectory(inputs.codeSource)?.toPath()?.toAbsolutePath()?.normalize()?.toFile()
        }
        return DesktopDataDirectoryPlan(
            dataDirectory = dataDirectory.toFile(),
            currentUserAnchor = home.toFile(),
            ownerAnchor = home.toFile(),
            baseDirectory = base.toFile(),
            legacyInstallationDataDirectory = legacy?.takeUnless {
                it.toPath().toAbsolutePath().normalize() == dataDirectory
            },
            isExplicitOverride = false,
        )
    }

    /** Validate root-to-base; create only missing standard parents in a safe user-owned home chain. */
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

    /** Protect an installation-relative legacy root whose parent may be system-owned. */
    fun validateLegacyParentChain(plan: DesktopDataDirectoryPlan, legacyDirectory: File) {
        val currentUserAnchor = plan.currentUserAnchor.toPath().toAbsolutePath().normalize()
        val expectedOwner = Files.getOwner(currentUserAnchor, LinkOption.NOFOLLOW_LINKS)
        val parent = requireNotNull(legacyDirectory.toPath().toAbsolutePath().normalize().parent) {
            "Legacy Desktop data cannot be a filesystem root"
        }
        val filesystemRoot = requireNotNull(parent.root) { "Legacy Desktop data has no filesystem root" }
        val trustedOwners = trustedOwners(parent, expectedOwner)
        var current = filesystemRoot
        validateStableParent(current, expectedOwner, trustedOwners, "Legacy Desktop data parent chain")
        for (component in filesystemRoot.relativize(parent)) {
            current = current.resolve(component)
            validateStableParent(current, expectedOwner, trustedOwners, "Legacy Desktop data parent chain")
        }
    }

    private fun legacyInstallationDataDirectory(codeSource: File): File? {
        val artifact = codeSource.absoluteFile
        val container = artifact.parentFile ?: return null
        val installationRoot = when (container.name) {
            "bin", "app" -> container.parentFile
            else -> container
        } ?: return null
        return File(installationRoot, "data")
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
            if (created) runCatching { Files.deleteIfExists(path) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
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
                runCatching {
                    path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(sid)
                }.getOrNull()
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
    private const val DEVELOPMENT_PROPERTY = "teamtalk.is.dev"
    private val PRIVATE_STANDARD_PARENT_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
    private val PRIVATE_STANDARD_PARENT_ATTRIBUTE =
        PosixFilePermissions.asFileAttribute(PRIVATE_STANDARD_PARENT_PERMISSIONS)
    private val WINDOWS_TRUSTED_SYSTEM_SIDS = listOf(
        "S-1-5-18", // Local System
        "S-1-5-32-544", // Built-in Administrators
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464", // TrustedInstaller
    )
}

/** Pure Windows ACL seam so Linux/macOS CI can protect the parent-directory policy. */
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

/** Resolved once before logging, locking, crash persistence or authentication is initialized. */
internal object DesktopEnvironment {
    fun prepareDataDirectory(): DesktopPreparedDataDirectory =
        DesktopDataMigration.prepare(DesktopDataDirectoryPolicy.resolve(DesktopDataDirectoryPolicy.currentInputs()))
}
