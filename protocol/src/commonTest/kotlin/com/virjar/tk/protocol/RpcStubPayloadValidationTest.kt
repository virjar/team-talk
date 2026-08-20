package com.virjar.tk.protocol

import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.rpc.gen.OrganizationRpcContract
import com.virjar.tk.rpc.gen.OrganizationRpcStub
import io.netty.handler.codec.CorruptedFrameException
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
            override suspend fun listUnits(): List<OrganizationUnit> = emptyList()

            override suspend fun listMembers(
                unitId: String,
                recursive: Boolean,
            ): List<OrganizationMember> {
                businessCalls++
                return emptyList()
            }
        }
        val valid = OrganizationRpcContract.encodeListMembers("unit-1", recursive = true)
        val malformed = valid + byteArrayOf(0x7f)

        assertFailsWith<CorruptedFrameException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_MEMBERS, malformed)
        }
        assertEquals(0, businessCalls)
    }

    @Test
    fun `no-argument request also validates before dispatch`() = runSuspend {
        var businessCalls = 0
        val stub = object : OrganizationRpcStub("actor") {
            override suspend fun listUnits(): List<OrganizationUnit> {
                businessCalls++
                return emptyList()
            }

            override suspend fun listMembers(
                unitId: String,
                recursive: Boolean,
            ): List<OrganizationMember> = emptyList()
        }

        assertFailsWith<CorruptedFrameException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_UNITS, byteArrayOf(1))
        }
        assertEquals(0, businessCalls)
    }

    @Test
    fun `required strings and booleans fail canonically before dispatch`() = runSuspend {
        var businessCalls = 0
        val stub = object : OrganizationRpcStub("actor") {
            override suspend fun listUnits(): List<OrganizationUnit> = emptyList()

            override suspend fun listMembers(
                unitId: String,
                recursive: Boolean,
            ): List<OrganizationMember> {
                businessCalls++
                return emptyList()
            }
        }

        assertFailsWith<CorruptedFrameException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_MEMBERS, byteArrayOf(0))
        }

        val invalidBoolean = OrganizationRpcContract.encodeListMembers("unit-1", recursive = true)
            .also { it[it.lastIndex] = 2 }
        assertFailsWith<CorruptedFrameException> {
            stub.dispatch(OrganizationRpcContract.M_LIST_MEMBERS, invalidBoolean)
        }
        assertEquals(0, businessCalls)
    }

    /** Generated stubs do not suspend in these tests, so no coroutine runtime is required. */
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
