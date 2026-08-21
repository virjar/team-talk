package com.virjar.tk.env

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDataDirectoryPolicyTest {
    @Test
    fun `default roots use each platform user app-data convention`() {
        val home = testHome()
        assertEquals(
            File(home, "Library/Application Support/TeamTalk"),
            resolve("Mac OS X", home).dataDirectory,
        )
        assertEquals(
            File(home, "AppData/Local/TeamTalk"),
            resolve(
                "Windows 11",
                home,
                environment = mapOf("LOCALAPPDATA" to File(home, "AppData/Local").absolutePath),
            ).dataDirectory,
        )
        assertEquals(
            File(home, ".xdg-data/teamtalk"),
            resolve(
                "Linux",
                home,
                environment = mapOf("XDG_DATA_HOME" to File(home, ".xdg-data").absolutePath),
            ).dataDirectory,
        )
    }

    @Test
    fun `relative XDG is ignored and development never claims repository data`() {
        val home = testHome()
        val plan = resolve(
            "Linux",
            home,
            environment = mapOf("XDG_DATA_HOME" to "relative-data"),
            isDevelopment = true,
        )
        assertEquals(File(home, ".local/share/teamtalk"), plan.dataDirectory)
        assertNull(plan.legacyInstallationDataDirectory)
    }

    @Test
    fun `production finds old install data while explicit override disables migration`() {
        val home = testHome()
        val codeSource = File(home, "installed/TeamTalk/app/teamtalk.jar")
        assertEquals(
            File(home, "installed/TeamTalk/data"),
            resolve("Linux", home, codeSource = codeSource)
                .legacyInstallationDataDirectory,
        )
        val explicitPath = File(home, "private/teamtalk-profile").absolutePath
        val explicit = resolve("Linux", home, explicit = explicitPath)
        assertEquals(File(explicitPath), explicit.dataDirectory)
        assertNull(explicit.legacyInstallationDataDirectory)
        assertTrue(explicit.isExplicitOverride)
        assertFailsWith<IllegalArgumentException> { resolve("Linux", home, explicit = "relative-profile") }
    }

    @Test
    fun `Windows parent ACL rejects untrusted mutation but permits read-only principals`() {
        val owner = Principal("owner")
        val system = Principal("system")
        val everyone = Principal("Everyone")
        val builtinUsers = Principal("BUILTIN\\Users")
        val ownerWrite = allow(owner, AclEntryPermission.WRITE_DATA)
        val systemWrite = allow(system, AclEntryPermission.DELETE_CHILD)
        val everyoneRead = allow(everyone, AclEntryPermission.READ_DATA)
        val everyoneWrite = allow(everyone, AclEntryPermission.APPEND_DATA)
        val usersDeleteChild = allow(builtinUsers, AclEntryPermission.DELETE_CHILD)

        assertTrue(
            WindowsSafeParentAclPolicy.isSafe(owner, setOf(system), listOf(ownerWrite, systemWrite, everyoneRead)),
        )
        assertFalse(
            WindowsSafeParentAclPolicy.isSafe(owner, setOf(system), listOf(ownerWrite, everyoneWrite)),
        )
        assertFalse(
            WindowsSafeParentAclPolicy.isSafe(owner, setOf(system), listOf(ownerWrite, usersDeleteChild)),
        )
        assertTrue(
            WindowsSafeParentAclPolicy.isSafe(
                owner,
                emptySet(),
                listOf(entry(AclEntryType.DENY, everyone, AclEntryPermission.WRITE_DATA)),
            ),
        )
        assertTrue(
            WindowsSafeParentAclPolicy.isSafe(
                owner,
                emptySet(),
                listOf(
                    entry(
                        AclEntryType.ALLOW,
                        everyone,
                        AclEntryPermission.WRITE_DATA,
                        setOf(AclEntryFlag.INHERIT_ONLY),
                    ),
                ),
            ),
        )
        assertFalse(
            WindowsSafeParentAclPolicy.isSafe(
                owner,
                emptySet(),
                listOf(
                    entry(
                        AclEntryType.ALLOW,
                        everyone,
                        AclEntryPermission.WRITE_DATA,
                        setOf(AclEntryFlag.INHERIT_ONLY, AclEntryFlag.DIRECTORY_INHERIT),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `prepare validates the whole parent chain and creates only safe standard parents`() {
        val realUserHome = File(requireNotNull(System.getProperty("user.dir")))
            .toPath()
            .toRealPath()
        if (Files.getFileAttributeView(realUserHome, PosixFileAttributeView::class.java) == null) return
        val home = Files.createTempDirectory(realUserHome, "teamtalk-policy-home-")
            .toRealPath(LinkOption.NOFOLLOW_LINKS)
        val external = Files.createTempDirectory(realUserHome, "teamtalk-policy-external-")
            .toRealPath(LinkOption.NOFOLLOW_LINKS)
        try {
            val standard = resolve("Linux", home.toFile(), isDevelopment = true)
            DesktopDataDirectoryPolicy.prepareBaseDirectory(standard)
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(home.resolve(".local"), LinkOption.NOFOLLOW_LINKS),
            )
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(home.resolve(".local/share"), LinkOption.NOFOLLOW_LINKS),
            )

            val externalXdg = resolve(
                "Linux",
                home.toFile(),
                environment = mapOf("XDG_DATA_HOME" to external.toString()),
                isDevelopment = true,
            )
            val explicit = resolve(
                "Linux",
                home.toFile(),
                explicit = external.resolve("profile").toString(),
            )
            DesktopDataDirectoryPolicy.prepareBaseDirectory(externalXdg)
            DesktopDataDirectoryPolicy.prepareBaseDirectory(explicit)

            val missingExplicit = resolve(
                "Linux",
                home.toFile(),
                explicit = home.resolve("missing-parent/profile").toString(),
            )
            assertFailsWith<IllegalArgumentException> {
                DesktopDataDirectoryPolicy.prepareBaseDirectory(missingExplicit)
            }
            assertFalse(Files.exists(home.resolve("missing-parent"), LinkOption.NOFOLLOW_LINKS))

            val realParent = Files.createDirectory(home.resolve("real-parent"))
            Files.setPosixFilePermissions(realParent, PosixFilePermissions.fromString("rwx------"))
            Files.createSymbolicLink(home.resolve("linked-parent"), realParent)
            val linked = resolve(
                "Linux",
                home.toFile(),
                explicit = home.resolve("linked-parent/profile").toString(),
            )
            assertFailsWith<IllegalArgumentException> {
                DesktopDataDirectoryPolicy.prepareBaseDirectory(linked)
            }

            Files.setPosixFilePermissions(external, PosixFilePermissions.fromString("rwxrwx---"))
            assertFailsWith<IllegalArgumentException> {
                DesktopDataDirectoryPolicy.prepareBaseDirectory(externalXdg)
            }
            assertFailsWith<IllegalArgumentException> {
                DesktopDataDirectoryPolicy.prepareBaseDirectory(explicit)
            }
        } finally {
            home.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
        }
    }

    private fun resolve(
        osName: String,
        home: File,
        environment: Map<String, String> = emptyMap(),
        explicit: String? = null,
        isDevelopment: Boolean = false,
        codeSource: File = File("/opt/TeamTalk/app/teamtalk.jar"),
    ): DesktopDataDirectoryPlan = DesktopDataDirectoryPolicy.resolve(
        DesktopDataDirectoryInputs(
            osName,
            home,
            environment,
            explicit,
            isDevelopment,
            codeSource,
        ),
    )

    private fun testHome(): File = File(
        requireNotNull(System.getProperty("user.home")),
        "teamtalk-policy-tests/alice",
    ).absoluteFile

    private fun allow(principal: UserPrincipal, permission: AclEntryPermission): AclEntry =
        entry(AclEntryType.ALLOW, principal, permission)

    private fun entry(
        type: AclEntryType,
        principal: UserPrincipal,
        permission: AclEntryPermission,
        flags: Set<AclEntryFlag> = emptySet(),
    ): AclEntry = AclEntry.newBuilder()
        .setType(type)
        .setPrincipal(principal)
        .setPermissions(permission)
        .setFlags(flags)
        .build()

    private data class Principal(private val value: String) : UserPrincipal {
        override fun getName(): String = value
    }
}
