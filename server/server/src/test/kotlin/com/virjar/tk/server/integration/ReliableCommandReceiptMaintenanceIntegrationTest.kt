package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.contact.ContactDecisionType
import com.virjar.tk.server.infra.db.ContactDecisionReceipts
import com.virjar.tk.server.infra.db.DocumentSpacePolicyCommands
import com.virjar.tk.server.infra.db.DocumentNodeMoveCommands
import com.virjar.tk.server.infra.db.InviteLinkCreationReceipts
import com.virjar.tk.server.infra.db.ReliableCommandReceiptCleanupConfig
import com.virjar.tk.server.infra.db.ReliableCommandReceiptMaintenance
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReliableCommandReceiptMaintenanceIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private const val NOW = 1_800_000_000_000L
        private val FINGERPRINT = "0".repeat(64)
    }

    @Test
    fun `global cleanup is batch bounded and never deletes the live boundary`() = runBlocking {
        val actor = deterministicUuid("maintenance-actor")
        val chatId = deterministicUuid("maintenance-chat")
        val contactPayload = ProtoCodec.encode(
            ContactApply(id = 1L, fromUid = "from", toUid = actor, status = 1),
        )
        val expiredIndexes = 0 until 5
        transaction(ext.env.database) {
            ContactDecisionReceipts.batchInsert(expiredIndexes, shouldReturnGeneratedValues = false) { index ->
                this[ContactDecisionReceipts.actorUid] = actor
                this[ContactDecisionReceipts.operationId] = deterministicUuid("expired-contact-$index")
                this[ContactDecisionReceipts.requestFingerprint] = FINGERPRINT
                this[ContactDecisionReceipts.decision] = ContactDecisionType.ACCEPT
                this[ContactDecisionReceipts.resultPayload] = contactPayload
                this[ContactDecisionReceipts.issuedAt] = NOW - 10L
                this[ContactDecisionReceipts.expiresAt] = NOW - index - 1L
                this[ContactDecisionReceipts.createdAt] = NOW - 10L
            }
            ContactDecisionReceipts.batchInsert(listOf(NOW, NOW + 1L), shouldReturnGeneratedValues = false) { expiry ->
                this[ContactDecisionReceipts.actorUid] = actor
                this[ContactDecisionReceipts.operationId] = deterministicUuid("live-contact-$expiry")
                this[ContactDecisionReceipts.requestFingerprint] = FINGERPRINT
                this[ContactDecisionReceipts.decision] = ContactDecisionType.ACCEPT
                this[ContactDecisionReceipts.resultPayload] = contactPayload
                this[ContactDecisionReceipts.issuedAt] = NOW
                this[ContactDecisionReceipts.expiresAt] = expiry
                this[ContactDecisionReceipts.createdAt] = NOW
            }

            InviteLinkCreationReceipts.batchInsert(expiredIndexes, shouldReturnGeneratedValues = false) { index ->
                this[InviteLinkCreationReceipts.actorUid] = actor
                this[InviteLinkCreationReceipts.operationId] = deterministicUuid("expired-invite-$index")
                this[InviteLinkCreationReceipts.requestFingerprint] = FINGERPRINT
                this[InviteLinkCreationReceipts.chatId] = chatId
                this[InviteLinkCreationReceipts.token] = deterministicUuid("expired-invite-token-$index")
                this[InviteLinkCreationReceipts.issuedAt] = NOW - 10L
                this[InviteLinkCreationReceipts.expiresAt] = NOW - index - 1L
                this[InviteLinkCreationReceipts.createdAt] = NOW - 10L
            }
            InviteLinkCreationReceipts.batchInsert(listOf(NOW, NOW + 1L), shouldReturnGeneratedValues = false) { expiry ->
                this[InviteLinkCreationReceipts.actorUid] = actor
                this[InviteLinkCreationReceipts.operationId] = deterministicUuid("live-invite-$expiry")
                this[InviteLinkCreationReceipts.requestFingerprint] = FINGERPRINT
                this[InviteLinkCreationReceipts.chatId] = chatId
                this[InviteLinkCreationReceipts.token] = deterministicUuid("live-invite-token-$expiry")
                this[InviteLinkCreationReceipts.issuedAt] = NOW
                this[InviteLinkCreationReceipts.expiresAt] = expiry
                this[InviteLinkCreationReceipts.createdAt] = NOW
            }

            DocumentSpacePolicyCommands.batchInsert(expiredIndexes, shouldReturnGeneratedValues = false) { index ->
                this[DocumentSpacePolicyCommands.actorUid] = actor
                this[DocumentSpacePolicyCommands.operationId] = deterministicUuid("expired-document-policy-$index")
                this[DocumentSpacePolicyCommands.spaceId] = deterministicUuid("document-policy-space")
                this[DocumentSpacePolicyCommands.mutationType] = 1
                this[DocumentSpacePolicyCommands.fingerprint] = FINGERPRINT
                this[DocumentSpacePolicyCommands.fromPolicyRevision] = 1L
                this[DocumentSpacePolicyCommands.resultingPolicyRevision] = 1L
                this[DocumentSpacePolicyCommands.issuedAt] = NOW - 10L
                this[DocumentSpacePolicyCommands.expiresAt] = NOW - index - 1L
                this[DocumentSpacePolicyCommands.createdAt] = NOW - 10L
            }
            DocumentSpacePolicyCommands.batchInsert(
                listOf(NOW, NOW + 1L),
                shouldReturnGeneratedValues = false,
            ) { expiry ->
                this[DocumentSpacePolicyCommands.actorUid] = actor
                this[DocumentSpacePolicyCommands.operationId] = deterministicUuid("live-document-policy-$expiry")
                this[DocumentSpacePolicyCommands.spaceId] = deterministicUuid("document-policy-space")
                this[DocumentSpacePolicyCommands.mutationType] = 2
                this[DocumentSpacePolicyCommands.fingerprint] = FINGERPRINT
                this[DocumentSpacePolicyCommands.fromPolicyRevision] = 2L
                this[DocumentSpacePolicyCommands.resultingPolicyRevision] = 3L
                this[DocumentSpacePolicyCommands.issuedAt] = NOW
                this[DocumentSpacePolicyCommands.expiresAt] = expiry
                this[DocumentSpacePolicyCommands.createdAt] = NOW
            }

            DocumentNodeMoveCommands.batchInsert(expiredIndexes, shouldReturnGeneratedValues = false) { index ->
                this[DocumentNodeMoveCommands.actorUid] = actor
                this[DocumentNodeMoveCommands.operationId] = deterministicUuid("expired-document-move-$index")
                this[DocumentNodeMoveCommands.spaceId] = deterministicUuid("document-move-space")
                this[DocumentNodeMoveCommands.nodeId] = deterministicUuid("document-move-node")
                this[DocumentNodeMoveCommands.fingerprint] = FINGERPRINT
                this[DocumentNodeMoveCommands.fromRevision] = 1L
                this[DocumentNodeMoveCommands.resultingRevision] = 2L
                this[DocumentNodeMoveCommands.issuedAt] = NOW - 10L
                this[DocumentNodeMoveCommands.expiresAt] = NOW - index - 1L
                this[DocumentNodeMoveCommands.createdAt] = NOW - 10L
            }
            DocumentNodeMoveCommands.batchInsert(
                listOf(NOW, NOW + 1L),
                shouldReturnGeneratedValues = false,
            ) { expiry ->
                this[DocumentNodeMoveCommands.actorUid] = actor
                this[DocumentNodeMoveCommands.operationId] = deterministicUuid("live-document-move-$expiry")
                this[DocumentNodeMoveCommands.spaceId] = deterministicUuid("document-move-space")
                this[DocumentNodeMoveCommands.nodeId] = deterministicUuid("document-move-node")
                this[DocumentNodeMoveCommands.fingerprint] = FINGERPRINT
                this[DocumentNodeMoveCommands.fromRevision] = 2L
                this[DocumentNodeMoveCommands.resultingRevision] = 3L
                this[DocumentNodeMoveCommands.issuedAt] = NOW
                this[DocumentNodeMoveCommands.expiresAt] = expiry
                this[DocumentNodeMoveCommands.createdAt] = NOW
            }
        }
        val maintenance = ReliableCommandReceiptMaintenance(
            database = ext.env.database,
            config = ReliableCommandReceiptCleanupConfig(
                batchSize = 2,
                maxBatchesPerTablePerRun = 2,
            ),
            wallClockMillis = { NOW },
        )

        val first = maintenance.cleanupExpiredReceipts()
        assertEquals(4, first.contactReceiptsDeleted)
        assertEquals(4, first.inviteReceiptsDeleted)
        assertEquals(4, first.documentPolicyReceiptsDeleted)
        assertEquals(4, first.documentNodeMoveReceiptsDeleted)
        assertTrue(first.contactBacklogMayRemain)
        assertTrue(first.inviteBacklogMayRemain)
        assertTrue(first.documentPolicyBacklogMayRemain)
        assertTrue(first.documentNodeMoveBacklogMayRemain)
        assertTrue(first.backlogMayRemain)
        assertEquals(1L, expiredContactCount(actor))
        assertEquals(1L, expiredInviteCount(actor))
        assertEquals(1L, expiredDocumentPolicyCount(actor))
        assertEquals(1L, expiredDocumentNodeMoveCount(actor))
        assertEquals(2L, liveContactCount(actor))
        assertEquals(2L, liveInviteCount(actor))
        assertEquals(2L, liveDocumentPolicyCount(actor))
        assertEquals(2L, liveDocumentNodeMoveCount(actor))

        val second = maintenance.cleanupExpiredReceipts()
        assertEquals(1, second.contactReceiptsDeleted)
        assertEquals(1, second.inviteReceiptsDeleted)
        assertEquals(1, second.documentPolicyReceiptsDeleted)
        assertEquals(1, second.documentNodeMoveReceiptsDeleted)
        assertFalse(second.contactBacklogMayRemain)
        assertFalse(second.inviteBacklogMayRemain)
        assertFalse(second.documentPolicyBacklogMayRemain)
        assertFalse(second.documentNodeMoveBacklogMayRemain)
        assertFalse(second.backlogMayRemain)
        assertEquals(0L, expiredContactCount(actor))
        assertEquals(0L, expiredInviteCount(actor))
        assertEquals(0L, expiredDocumentPolicyCount(actor))
        assertEquals(0L, expiredDocumentNodeMoveCount(actor))
        assertEquals(2L, liveContactCount(actor))
        assertEquals(2L, liveInviteCount(actor))
        assertEquals(2L, liveDocumentPolicyCount(actor))
        assertEquals(2L, liveDocumentNodeMoveCount(actor))
    }

    private fun expiredContactCount(actor: String): Long = transaction(ext.env.database) {
        ContactDecisionReceipts.selectAll().where {
            (ContactDecisionReceipts.actorUid eq actor) and
                (ContactDecisionReceipts.expiresAt less NOW)
        }.count()
    }

    private fun expiredInviteCount(actor: String): Long = transaction(ext.env.database) {
        InviteLinkCreationReceipts.selectAll().where {
            (InviteLinkCreationReceipts.actorUid eq actor) and
                (InviteLinkCreationReceipts.expiresAt less NOW)
        }.count()
    }

    private fun liveContactCount(actor: String): Long = transaction(ext.env.database) {
        ContactDecisionReceipts.selectAll().where {
            (ContactDecisionReceipts.actorUid eq actor) and
                (ContactDecisionReceipts.expiresAt greaterEq NOW)
        }.count()
    }

    private fun liveInviteCount(actor: String): Long = transaction(ext.env.database) {
        InviteLinkCreationReceipts.selectAll().where {
            (InviteLinkCreationReceipts.actorUid eq actor) and
                (InviteLinkCreationReceipts.expiresAt greaterEq NOW)
        }.count()
    }

    private fun expiredDocumentPolicyCount(actor: String): Long = transaction(ext.env.database) {
        DocumentSpacePolicyCommands.selectAll().where {
            (DocumentSpacePolicyCommands.actorUid eq actor) and
                (DocumentSpacePolicyCommands.expiresAt less NOW)
        }.count()
    }

    private fun liveDocumentPolicyCount(actor: String): Long = transaction(ext.env.database) {
        DocumentSpacePolicyCommands.selectAll().where {
            (DocumentSpacePolicyCommands.actorUid eq actor) and
                (DocumentSpacePolicyCommands.expiresAt greaterEq NOW)
        }.count()
    }

    private fun expiredDocumentNodeMoveCount(actor: String): Long = transaction(ext.env.database) {
        DocumentNodeMoveCommands.selectAll().where {
            (DocumentNodeMoveCommands.actorUid eq actor) and
                (DocumentNodeMoveCommands.expiresAt less NOW)
        }.count()
    }

    private fun liveDocumentNodeMoveCount(actor: String): Long = transaction(ext.env.database) {
        DocumentNodeMoveCommands.selectAll().where {
            (DocumentNodeMoveCommands.actorUid eq actor) and
                (DocumentNodeMoveCommands.expiresAt greaterEq NOW)
        }.count()
    }

    private fun deterministicUuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.encodeToByteArray()).toString()
}
