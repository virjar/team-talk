package com.virjar.tk.infra.db

import org.jetbrains.exposed.sql.TextColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaDeclarationTest {
    @Test
    fun `fresh schema declares pending friend uniqueness directly`() {
        val index = FriendApplies.indices.single {
            it.customName == "uq_friend_applies_pending_direction"
        }

        assertTrue(index.unique)
        assertEquals(listOf(FriendApplies.fromUid, FriendApplies.toUid), index.columns)
        assertNotNull(index.filterCondition, "pending uniqueness must remain a partial index")
    }

    @Test
    fun `fresh schema contains the final draft type and explicit epoch`() {
        assertIs<TextColumnType>(Conversations.draft.columnType)
        assertEquals(3, DatabaseFactory.CURRENT_SCHEMA_EPOCH)
    }

    @Test
    fun `fresh schema guards chat and organization aggregate invariants`() {
        val personalPair = Chats.indices.single { it.columns == listOf(Chats.personalKey) }
        assertTrue(personalPair.unique)

        val activeOwner = GroupMembers.indices.single {
            it.customName == "uq_group_members_active_owner"
        }
        assertTrue(activeOwner.unique)
        assertEquals(listOf(GroupMembers.chatId), activeOwner.columns)
        assertNotNull(activeOwner.filterCondition)

        val memberMute = GroupMemberMutes.indices.single {
            it.customName == "uq_group_member_mute_chat_uid"
        }
        assertTrue(memberMute.unique)
        assertEquals(listOf(GroupMemberMutes.chatId, GroupMemberMutes.uid), memberMute.columns)

        val primaryMembership = OrganizationMemberships.indices.single {
            it.customName == "uq_org_membership_primary_uid"
        }
        assertTrue(primaryMembership.unique)
        assertEquals(listOf(OrganizationMemberships.uid), primaryMembership.columns)
        assertNotNull(primaryMembership.filterCondition)
    }
}
