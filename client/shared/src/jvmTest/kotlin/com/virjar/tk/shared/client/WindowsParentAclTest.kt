package com.virjar.tk.shared.client

import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.UserPrincipal
import kotlin.test.Test
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

    private fun allow(principal: UserPrincipal, permissions: Set<AclEntryPermission>, flags: Set<AclEntryFlag> = emptySet()) =
        AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
            .setPermissions(permissions).setFlags(flags).build()

    private data class Principal(private val value: String) : UserPrincipal {
        override fun getName() = value
    }
}
