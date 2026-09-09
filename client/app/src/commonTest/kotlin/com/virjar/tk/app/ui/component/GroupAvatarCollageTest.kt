package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupAvatarCollageTest {

    private fun member(uid: String, user: User?): Member =
        Member(uid = uid, chatId = "chat-1", role = 0, user = user)

    private fun user(uid: String) = User(uid = uid, username = "u-$uid", name = "成员$uid")

    @Test
    fun `picks first distinct members with user projection up to limit`() {
        val members = listOf(
            member("u3", user("3")),
            member("u1", user("1")),
            member("u1", user("1")), // 重复 uid 只取一次
            member("u2", user("2")),
            member("u4", user("4")),
            member("u5", user("5")), // 超出 2×2 上限被截断
        )
        assertEquals(listOf("3", "1", "2", "4"), groupAvatarCellUsers(members).map { it.uid })
    }

    @Test
    fun `members without user projection are skipped instead of rendering question marks`() {
        val members = listOf(
            member("a", null),
            member("b", user("b")),
        )
        assertEquals(listOf("b"), groupAvatarCellUsers(members).map { it.uid })
        assertTrue(groupAvatarCellUsers(members).all { it.name.isNotBlank() })
    }

    @Test
    fun `empty members yield empty cells`() {
        assertEquals(emptyList(), groupAvatarCellUsers(emptyList()))
        assertEquals(emptyList(), groupAvatarCellUsers(listOf(member("a", null))))
    }
}
