package com.virjar.tk.shared.client

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDataCleanupRecoveryTest {
    @Test
    fun `credential persistence failure keeps cleanup pending until a fresh process successfully retries`() {
        val directory = createTempDirectory("account-cleanup-recovery-").toFile()
        val owner = AccountDataOwner("a".repeat(64), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "banned-user")
        try {
            val account = directory.resolve("users/${owner.uid}").apply { mkdirs() }
            account.resolve("messages.db").writeText("messages")
            val cleanup = AccountDataCleanup(directory) {
                listOf(AccountDataCleanupTarget.tree(directory, "users", it.uid))
            }
            cleanup.begin(owner)
            assertFailsWith<IllegalStateException> {
                resumePendingAccountCleanup(cleanup) { error("credential store unavailable") }
            }
            assertFalse(account.exists())
            assertEquals(listOf(owner), cleanup.pendingOwners())

            var clearedOwner: AccountDataOwner? = null
            val restarted = AccountDataCleanup(directory) {
                listOf(AccountDataCleanupTarget.tree(directory, "users", it.uid))
            }
            resumePendingAccountCleanup(restarted) { clearedOwner = it }
            assertEquals(owner, clearedOwner)
            assertTrue(restarted.pendingOwners().isEmpty())

            // 已完成的恢复绝不会清除后续登录可能已写入的凭据。
            resumePendingAccountCleanup(restarted) { error("completed cleanup must not replay") }
        } finally {
            directory.deleteRecursively()
        }
    }
}
