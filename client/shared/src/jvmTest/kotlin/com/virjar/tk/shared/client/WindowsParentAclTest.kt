package com.virjar.tk.shared.client

import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.UserPrincipal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsParentAclTest {
    private val user = Principal("current user")
    private val system = Principal("SYSTEM")
    private val administrators = Principal("Administrators")
    private val authenticatedUsers = Principal("Authenticated Users")
    private val trusted = setOf(system, administrators)

    @Test
    fun `default drive root ACL permits child creation without trusting all authenticated users`() {
        val acl = listOf(
            allow(system, AclEntryPermission.values().toSet()),
            allow(administrators, AclEntryPermission.values().toSet()),
            allow(authenticatedUsers, setOf(AclEntryPermission.APPEND_DATA)),
            allow(authenticatedUsers, setOf(AclEntryPermission.DELETE), setOf(
                AclEntryFlag.INHERIT_ONLY, AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT,
            )),
        )
        assertTrue(WindowsSafeParentAclPolicy.isSafe(user, trusted, acl))
        // 同一个主体获得真实的删除/改权限能力时仍需拒绝，不能以“系统默认主体”一概放行。
        for (permission in listOf(AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.WRITE_ATTRIBUTES)) {
            assertFalse(WindowsSafeParentAclPolicy.isSafe(user, trusted,
                acl + allow(authenticatedUsers, setOf(permission))))
        }
    }

    @Test
    fun `each existing child checks inherited entries that now apply to itself`() {
        val parentOnly = allow(authenticatedUsers, setOf(AclEntryPermission.DELETE), setOf(AclEntryFlag.INHERIT_ONLY))
        val effectiveChild = allow(authenticatedUsers, setOf(AclEntryPermission.DELETE), setOf(AclEntryFlag.DIRECTORY_INHERIT))
        assertTrue(WindowsSafeParentAclPolicy.isSafe(user, trusted, listOf(parentOnly)))
        assertFalse(WindowsSafeParentAclPolicy.isSafe(user, trusted, listOf(effectiveChild)))
    }

    @Test
    fun `current user and system can maintain parents while other read only entries remain valid`() {
        assertTrue(WindowsSafeParentAclPolicy.isSafe(user, trusted, listOf(
            allow(user, AclEntryPermission.values().toSet()),
            allow(system, AclEntryPermission.values().toSet()),
            allow(authenticatedUsers, setOf(AclEntryPermission.READ_DATA, AclEntryPermission.EXECUTE)),
        )))
    }

    @Test
    fun `failure snapshot preserves rejected ACE details while masking current account and profile`() {
        val current = Principal("PRIVATE-PC\\SensitiveUser")
        val packagePrincipal = Principal("APPLICATION PACKAGE AUTHORITY\\S-1-15-2-42")
        val acceptedOwners = setOf(current, system, administrators)
        val entries = listOf(
            allow(current, setOf(AclEntryPermission.WRITE_OWNER)),
            allow(system, setOf(AclEntryPermission.DELETE)),
            allow(authenticatedUsers, setOf(AclEntryPermission.READ_DATA)),
            allow(authenticatedUsers, setOf(AclEntryPermission.DELETE), setOf(AclEntryFlag.INHERIT_ONLY)),
            allow(packagePrincipal, setOf(AclEntryPermission.WRITE_ACL), setOf(AclEntryFlag.DIRECTORY_INHERIT)),
        )
        assertFalse(WindowsSafeParentAclPolicy.isSafe(current, acceptedOwners, entries))
        val report = describeWindowsParentAclFailure(
            Path.of("C:\\Users\\SensitiveUser\\AppData"), current, system, acceptedOwners, entries,
        )
        assertFalse(report.contains("PRIVATE-PC"))
        assertFalse(report.contains("SensitiveUser"))
        assertContains(report, "path=C:\\Users\\<user>\\AppData")
        assertContains(report, "currentOwner=p1(<current-user>)")
        assertContains(report, "actualOwner=p2(SYSTEM)")
        assertContains(report, "trustedOwners=")
        val rejected = report.lineSequence().single { it.startsWith("ACE[4]") }
        assertContains(rejected, packagePrincipal.getName())
        assertContains(rejected, "type=ALLOW")
        assertContains(rejected, "flags=[DIRECTORY_INHERIT]")
        assertContains(rejected, "permissions=[WRITE_ACL]")
        assertContains(rejected, "result=REJECT_UNTRUSTED_MUTATION")
        assertContains(report, "result=INHERIT_ONLY")
        assertContains(report, "result=NO_PARENT_MUTATION")
        assertContains(report, "reason=Untrusted ALLOW entry can modify this existing directory")
    }

    @Test
    fun `bounded failure snapshot includes a rejected ACE after many accepted entries`() {
        val entries = List(100) { index ->
            allow(Principal("reader-$index"), setOf(AclEntryPermission.READ_DATA))
        } + allow(Principal("APPLICATION PACKAGE AUTHORITY\\S-1-15-2-99"), setOf(AclEntryPermission.DELETE_CHILD))
        val report = describeWindowsParentAclFailure(Path.of("C:\\Users"), user, system, trusted, entries)
        assertTrue(report.length <= 24 * 1024)
        assertContains(report, "entries=101, rejected=1, shown=32, omitted=69")
        assertContains(report.lineSequence().first { it.startsWith("ACE[") }, "ACE[100]")
        assertContains(report, "permissions=[DELETE_CHILD] result=REJECT_UNTRUSTED_MUTATION")
    }

    private fun allow(principal: UserPrincipal, permissions: Set<AclEntryPermission>, flags: Set<AclEntryFlag> = emptySet()) =
        AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
            .setPermissions(permissions).setFlags(flags).build()

    private data class Principal(private val value: String) : UserPrincipal {
        override fun getName() = value
    }
}
