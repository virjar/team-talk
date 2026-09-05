package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.OrganizationMemberPage
import com.virjar.tk.protocol.model.OrganizationMemberPageRequest
import com.virjar.tk.protocol.model.OrganizationUnitPage
import com.virjar.tk.protocol.model.OrganizationUnitPageRequest
import com.virjar.tk.protocol.rpc.gen.OrganizationRpcContract
import com.virjar.tk.protocol.rpc.gen.OrganizationRpcStub
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RpcStubPayloadValidationTest {

    @Test
    fun `trailing request bytes are rejected before business method is called`() = runSuspend {
        var businessCalls = 0
        val stub = object : OrganizationRpcStub("actor") {
            override suspend fun listUnitPage(request: OrganizationUnitPageRequest) =
                OrganizationUnitPage(0, emptyList(), null)

            override suspend fun listMemberPage(
                request: OrganizationMemberPageRequest,
            ): OrganizationMemberPage {
                businessCalls++
                return OrganizationMemberPage(0, emptyList(), null)
            }
        }
        val valid = OrganizationRpcContract.encodeListMemberPage(
            OrganizationMemberPageRequest("unit-1", recursive = true),
        )
        val malformed = valid + byteArrayOf(0x7f)

        assertFailsWith<ProtocolCorruptionException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_MEMBER_PAGE, malformed)
        }
        assertEquals(0, businessCalls)
    }

    @Test
    fun `unit page request validates before dispatch`() = runSuspend {
        var businessCalls = 0
        val stub = object : OrganizationRpcStub("actor") {
            override suspend fun listUnitPage(request: OrganizationUnitPageRequest): OrganizationUnitPage {
                businessCalls++
                return OrganizationUnitPage(0, emptyList(), null)
            }

            override suspend fun listMemberPage(request: OrganizationMemberPageRequest) =
                OrganizationMemberPage(0, emptyList(), null)
        }

        assertFailsWith<ProtocolCorruptionException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_UNIT_PAGE, byteArrayOf(1, 0x7f))
        }
        assertEquals(0, businessCalls)
    }

    @Test
    fun `required strings and booleans fail canonically before dispatch`() = runSuspend {
        var businessCalls = 0
        val stub = object : OrganizationRpcStub("actor") {
            override suspend fun listUnitPage(request: OrganizationUnitPageRequest) =
                OrganizationUnitPage(0, emptyList(), null)

            override suspend fun listMemberPage(
                request: OrganizationMemberPageRequest,
            ): OrganizationMemberPage {
                businessCalls++
                return OrganizationMemberPage(0, emptyList(), null)
            }
        }

        assertFailsWith<ProtocolCorruptionException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_MEMBER_PAGE, byteArrayOf(0))
        }

        val invalidBoolean = OrganizationRpcContract.encodeListMemberPage(
            OrganizationMemberPageRequest("unit-1", recursive = true),
        ).also { payload ->
            val recursiveOffset = ProtoCodec.encodePayload { writeString("unit-1") }.size
            payload[recursiveOffset] = 2
        }
        assertFailsWith<ProtocolCorruptionException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_MEMBER_PAGE, invalidBoolean)
        }
        assertEquals(0, businessCalls)
    }

    /** 这些测试中生成的 stub 不会挂起，因此无需协程运行时。 */
    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return checkNotNull(outcome) { "test coroutine unexpectedly suspended" }.getOrThrow()
    }
}
