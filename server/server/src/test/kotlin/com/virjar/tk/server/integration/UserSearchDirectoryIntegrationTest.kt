package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserSearchDirectoryIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private const val STATUS_ACTIVE = 1
        private const val STATUS_DISABLED = 2
    }

    private val ctx get() = ext.env

    @Test
    fun `public directory filters before deterministic limit`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val keyword = "directory$suffix"
        seedUser("$keyword-00-bot", "$keyword 00 Bot", role = UserRole.BOT)
        seedUser("$keyword-01-system", "$keyword 01 System", role = UserRole.SYSTEM)
        seedUser("$keyword-02-disabled", "$keyword 02 Disabled", status = STATUS_DISABLED)
        val alpha = seedUser("$keyword-12-alpha", "$keyword 12 Alpha")
        val charlie = seedUser("$keyword-30-charlie", "$keyword 30 Charlie")
        val bravo = seedUser("$keyword-20-bravo", "$keyword 20 Bravo")

        val first = ctx.userService.search(keyword, limit = 2)
        val repeated = ctx.userService.search(keyword, limit = 2)

        assertEquals(listOf(alpha, bravo), first.map { it.uid })
        assertEquals(first, repeated)
        assertTrue(first.all { it.role == UserRole.HUMAN && it.status == STATUS_ACTIVE })
        assertTrue(charlie !in first.map { it.uid })
    }

    private fun seedUser(
        username: String,
        name: String,
        role: Int = UserRole.HUMAN,
        status: Int = STATUS_ACTIVE,
    ): String {
        val uid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            Users.insert {
                it[Users.uid] = uid
                it[Users.username] = username
                it[Users.name] = name
                it[Users.passwordHash] = "!integration-directory-fixture"
                it[Users.role] = role
                it[Users.status] = status
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
        }
        return uid
    }
}
