package com.virjar.tk

import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.model.User
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationModelTest {
    @Test
    fun `organization unit round trips`() {
        val unit = OrganizationUnit("engineering", "company", "研发部", "u1", 20, "chat-1")
        assertEquals(unit, ProtoCodec.decode(OrganizationUnit, ProtoCodec.encode(unit)))
    }

    @Test
    fun `organization member round trips with user projection`() {
        val member = OrganizationMember(
            unitId = "engineering",
            uid = "u1",
            title = "负责人",
            primary = true,
            joinedAt = 123,
            user = User("u1", "alice", "Alice"),
        )
        assertEquals(member, ProtoCodec.decode(OrganizationMember, ProtoCodec.encode(member)))
    }
}
