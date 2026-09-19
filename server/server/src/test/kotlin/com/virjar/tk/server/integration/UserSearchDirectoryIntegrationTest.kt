package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.Friends
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

        /** 纯字母唯一标记：拼音查询路径只对纯字母关键词生效（T062）。 */
        private fun letterMarker(): String =
            UUID.randomUUID().toString().replace("-", "").filter(Char::isLetter).take(10)
                .ifEmpty { "marker" }
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

        val caller = UUID.randomUUID().toString()
        val first = ctx.userService.search(caller, keyword, limit = 2)
        val repeated = ctx.userService.search(caller, keyword, limit = 2)

        assertEquals(listOf(alpha, bravo), first.map { it.uid })
        assertEquals(first, repeated)
        assertTrue(first.all { it.role == UserRole.HUMAN && it.status == STATUS_ACTIVE })
        assertTrue(charlie !in first.map { it.uid })
    }

    @Test
    fun `full pinyin matches CJK display names for plain callers`() {
        val marker = letterMarker()
        val target = seedUser(
            "pinyin-full-$marker",
            "张三$marker",
            namePinyinFull = "zhangsan$marker",
            namePinyinInitials = "zs$marker",
        )

        val caller = UUID.randomUUID().toString()
        val hits = ctx.userService.search(caller, "zhangsan$marker")
        assertEquals(listOf(target), hits.map { it.uid })

        val prefixHits = ctx.userService.search(caller, "zhangsan")
        assertTrue(target in prefixHits.map { it.uid })
    }

    @Test
    fun `short initials query is rejected for guests without org or friends`() {
        val guest = UUID.randomUUID().toString()
        val failure = assertThrows<IllegalArgumentException> {
            ctx.userService.search(guest, "zs")
        }
        assertEquals("搜索关键词太短：字母/数字至少 3 个字符", failure.message)
    }

    @Test
    fun `short initials match is scoped to org members and caller friends`() {
        val marker = letterMarker()
        val caller = seedUser("pinyin-member-$marker", "调用者$marker")
        val orgTarget = seedUser(
            "pinyin-org-$marker",
            "张三$marker",
            namePinyinFull = "zhangsan$marker",
            namePinyinInitials = "zs",
        )
        seedOrgMembership(orgTarget)
        // 组织外、非好友的目标：即使拼音键同样以 zs 开头，短首拼也不应把它带给成员调用者。
        val outsider = seedUser(
            "pinyin-outsider-$marker",
            "张四$marker",
            namePinyinFull = "zhangsi$marker",
            namePinyinInitials = "zsi",
        )

        seedOrgMembership(caller)
        val initialsHits = ctx.userService.search(caller, "zs")
        assertEquals(listOf(orgTarget), initialsHits.map { it.uid })
    }

    @Test
    fun `friend callers reach their friends by short initials`() {
        val marker = letterMarker()
        val caller = seedUser("pinyin-friend-caller-$marker", "好友调用者$marker")
        val friend = seedUser(
            "pinyin-friend-$marker",
            "李四$marker",
            namePinyinFull = "lisi$marker",
            namePinyinInitials = "ls",
        )
        seedFriendship(uid = caller, friendUid = friend)
        val stranger = seedUser(
            "pinyin-stranger-$marker",
            "王五$marker",
            namePinyinFull = "wangwu$marker",
            namePinyinInitials = "ww",
        )

        val hits = ctx.userService.search(caller, "ls")
        assertEquals(listOf(friend), hits.map { it.uid })

        // 访客（无组织、无好友）即使存在同名拼音键，短首拼依旧整体拒绝。
        val guest = UUID.randomUUID().toString()
        assertThrows<IllegalArgumentException> { ctx.userService.search(guest, "ww") }
    }

    private fun seedUser(
        username: String,
        name: String,
        role: Int = UserRole.HUMAN,
        status: Int = STATUS_ACTIVE,
        namePinyinFull: String = "",
        namePinyinInitials: String = "",
    ): String {
        val uid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            Users.insert {
                it[Users.uid] = uid
                it[Users.username] = username
                it[Users.name] = name
                it[Users.namePinyinFull] = namePinyinFull
                it[Users.namePinyinInitials] = namePinyinInitials
                it[Users.passwordHash] = "!integration-directory-fixture"
                it[Users.role] = role
                it[Users.status] = status
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
        }
        return uid
    }

    private fun seedOrgMembership(uid: String) {
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            OrganizationMemberships.insert {
                it[unitId] = UUID.randomUUID().toString()
                it[OrganizationMemberships.uid] = uid
                it[primary] = true
                it[joinedAt] = now
                it[updatedAt] = now
            }
        }
    }

    private fun seedFriendship(uid: String, friendUid: String) {
        transaction(ctx.database) {
            Friends.insert {
                it[Friends.uid] = uid
                it[Friends.friendUid] = friendUid
                it[status] = 1
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }
}
