package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReliableSocialCommandPersistenceTest {
    @Test
    fun `contact and invite commands survive restart and exact generations clear independently`() {
        val root = createTempDirectory("social-command-outbox-").toFile()
        val database = root.resolve("cache.db")
        try {
            open(database, createSchema = true).let { cache ->
                assertEquals(contact, cache.preparePendingContactDecision(contact))
                assertEquals(invite, cache.preparePendingInviteLinkCreation(invite))
                cache.close()
            }

            open(database).let { cache ->
                assertEquals(listOf(contact), cache.getPendingContactDecisions())
                assertEquals(listOf(invite), cache.getPendingInviteLinkCreations())
                assertFalse(cache.clearPendingContactDecision(OTHER_OPERATION_ID))
                assertFalse(cache.clearPendingInviteLinkCreation(OTHER_OPERATION_ID))
                assertTrue(cache.clearPendingContactDecision(contact.operationId))
                cache.close()
            }

            open(database).let { cache ->
                assertTrue(cache.getPendingContactDecisions().isEmpty())
                assertEquals(listOf(invite), cache.getPendingInviteLinkCreations())
                assertTrue(cache.clearPendingInviteLinkCreation(invite.operationId))
                cache.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `projection reset preserves unknown-result social commands`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.preparePendingContactDecision(contact)
        cache.preparePendingInviteLinkCreation(invite)

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertEquals(listOf(contact), cache.getPendingContactDecisions())
        assertEquals(listOf(invite), cache.getPendingInviteLinkCreations())
        cache.close()
    }

    @Test
    fun `social command outboxes reject overflow without replacing durable entries`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try {
            repeat(MAX_PENDING_CONTACT_DECISIONS) { index ->
                cache.preparePendingContactDecision(
                    PendingContactDecision(
                        operationId = deterministicUuid("contact-operation-$index"),
                        token = deterministicUuid("contact-token-$index"),
                        decision = PendingContactDecisionType.ACCEPT,
                        createdAt = index.toLong(),
                    ),
                )
            }
            assertFailsWith<IllegalStateException> {
                cache.preparePendingContactDecision(
                    PendingContactDecision(
                        operationId = deterministicUuid("contact-operation-overflow"),
                        token = deterministicUuid("contact-token-overflow"),
                        decision = PendingContactDecisionType.ACCEPT,
                        createdAt = MAX_PENDING_CONTACT_DECISIONS.toLong(),
                    ),
                )
            }
            assertEquals(MAX_PENDING_CONTACT_DECISIONS, cache.getPendingContactDecisions().size)

            repeat(MAX_PENDING_INVITE_LINK_CREATIONS) { index ->
                cache.preparePendingInviteLinkCreation(
                    PendingInviteLinkCreation(
                        operationId = deterministicUuid("invite-operation-$index"),
                        chatId = deterministicUuid("invite-chat-$index"),
                        name = "invite-$index",
                        maxUses = 0,
                        expiresAt = 0L,
                        createdAt = index.toLong(),
                    ),
                )
            }
            assertFailsWith<IllegalStateException> {
                cache.preparePendingInviteLinkCreation(
                    PendingInviteLinkCreation(
                        operationId = deterministicUuid("invite-operation-overflow"),
                        chatId = deterministicUuid("invite-chat-overflow"),
                        name = "overflow",
                        maxUses = 0,
                        expiresAt = 0L,
                        createdAt = MAX_PENDING_INVITE_LINK_CREATIONS.toLong(),
                    ),
                )
            }
            assertEquals(MAX_PENDING_INVITE_LINK_CREATIONS, cache.getPendingInviteLinkCreations().size)
        } finally {
            cache.close()
        }
    }

    private fun deterministicUuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.encodeToByteArray()).toString()

    private fun open(database: java.io.File, createSchema: Boolean = false): LocalCache {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
        if (createSchema) AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private companion object {
        val contact = PendingContactDecision(
            operationId = "00000000-0000-4000-8000-000000000091",
            token = "00000000-0000-4000-8000-000000000092",
            decision = PendingContactDecisionType.ACCEPT,
            createdAt = 1L,
        )
        val invite = PendingInviteLinkCreation(
            operationId = "00000000-0000-4000-8000-000000000093",
            chatId = "00000000-0000-4000-8000-000000000094",
            name = "项目邀请",
            maxUses = 3,
            expiresAt = 0L,
            createdAt = 2L,
        )
        const val OTHER_OPERATION_ID = "00000000-0000-4000-8000-000000000095"
    }
}
