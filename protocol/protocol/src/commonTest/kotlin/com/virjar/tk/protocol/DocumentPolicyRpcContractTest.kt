package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.rpc.gen.DocumentRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DocumentPolicyRpcContractTest {
    @Test
    fun `upsert grant freezes CAS revision before its stable operation identity`() {
        val spaceId = "00000000-0000-4000-8000-000000000041"
        val principalId = "00000000-0000-4000-8000-000000000042"
        val operationId = "00000000-0000-4000-8000-000000000043"
        val issuedAt = 1_701_234_567_890L

        ProtoCodec.withPayload(
            DocumentRpcContract.encodeUpsertGrant(
                spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                principalId,
                DocumentSpace.ROLE_EDITOR,
                false,
                17,
                operationId,
                issuedAt,
            ),
        ) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.upsertGrant.spaceId"))
            assertEquals(DocumentSpaceGrant.PRINCIPAL_USER, readVarInt())
            assertEquals(principalId, readRequiredString(fieldName = "document.upsertGrant.principalId"))
            assertEquals(DocumentSpace.ROLE_EDITOR, readVarInt())
            assertFalse(readBoolean())
            assertEquals(17L, readVarLong())
            assertEquals(operationId, readRequiredString(fieldName = "document.upsertGrant.operationId"))
            assertEquals(issuedAt, readVarLong())
        }
    }

    @Test
    fun `remove grant freezes CAS revision before its stable operation identity`() {
        val spaceId = "00000000-0000-4000-8000-000000000051"
        val principalId = "00000000-0000-4000-8000-000000000052"
        val operationId = "00000000-0000-4000-8000-000000000053"
        val issuedAt = 1_701_234_567_891L

        ProtoCodec.withPayload(
            DocumentRpcContract.encodeRemoveGrant(
                spaceId,
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                principalId,
                29,
                operationId,
                issuedAt,
            ),
        ) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.removeGrant.spaceId"))
            assertEquals(DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT, readVarInt())
            assertEquals(principalId, readRequiredString(fieldName = "document.removeGrant.principalId"))
            assertEquals(29L, readVarLong())
            assertEquals(operationId, readRequiredString(fieldName = "document.removeGrant.operationId"))
            assertEquals(issuedAt, readVarLong())
        }
    }
}
