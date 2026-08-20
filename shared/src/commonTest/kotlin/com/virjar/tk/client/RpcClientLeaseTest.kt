package com.virjar.tk.client

import com.virjar.tk.protocol.payload.InvokePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RpcClientLeaseTest {
    @Test
    fun `retired session request cannot cross the register to send gap into a replacement owner`() = runTest {
        val transport = BarrierRpcRequestTransport()
        val rpc = RpcClient(transport)
        rpc.start()

        val invocation = async {
            rpc.invoke("conversation", 7, byteArrayOf(1))
        }
        transport.sendEntered.await()

        // Retire account A after its pending request was registered but before the EventLoop send
        // decision. The reusable transport then becomes an authenticated account-B connection.
        rpc.stop()
        transport.replaceAuthenticatedOwner(ownerGeneration = 2L, connectionGeneration = 22L)
        transport.releaseSend.complete(Unit)

        assertFailsWith<CancellationException> { invocation.await() }
        assertTrue(transport.sentPayloads.isEmpty(), "retired account-A payload reached account B")
    }

    @Test
    fun `accepted send task that never executes is bounded by the whole RPC timeout`() = runTest {
        val transport = NeverExecutingRpcRequestTransport()
        val rpc = RpcClient(transport)
        rpc.start()

        val response = rpc.invoke("conversation", 7, byteArrayOf(1))

        assertEquals(504, response.status)
        rpc.stop()
    }

    @Test
    fun `caller cancellation is not converted into an RPC timeout`() = runTest {
        val transport = BarrierRpcRequestTransport()
        val rpc = RpcClient(transport)
        rpc.start()
        val invocation = async {
            rpc.invoke("conversation", 7, byteArrayOf(1))
        }
        transport.sendEntered.await()

        invocation.cancel(CancellationException("caller cancelled"))

        val cancellation = assertFailsWith<CancellationException> { invocation.await() }
        assertTrue(cancellation.message.orEmpty().contains("caller cancelled"))
        assertTrue(transport.sentPayloads.isEmpty())
        rpc.stop()
    }
}

private class NeverExecutingRpcRequestTransport : RpcRequestTransport {
    override val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
    override val routedPackets: Flow<RoutedPacket> = MutableSharedFlow()
    override val transportDisconnectEpoch: StateFlow<Long> = MutableStateFlow(0L)
    override val currentOwnerGeneration: Long = 1L
    override val currentConnectionGeneration: Long = 11L

    override suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        leaseIsActive: () -> Boolean,
        payload: InvokePayload,
    ): Boolean = awaitCancellation()
}

private class BarrierRpcRequestTransport : RpcRequestTransport {
    override val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
    override val routedPackets: Flow<RoutedPacket> = MutableSharedFlow()
    override val transportDisconnectEpoch: StateFlow<Long> = MutableStateFlow(0L)

    @Volatile
    override var currentOwnerGeneration: Long = 1L
        private set

    @Volatile
    override var currentConnectionGeneration: Long = 11L
        private set

    val sendEntered = CompletableDeferred<Unit>()
    val releaseSend = CompletableDeferred<Unit>()
    val sentPayloads = mutableListOf<InvokePayload>()

    override suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        leaseIsActive: () -> Boolean,
        payload: InvokePayload,
    ): Boolean {
        sendEntered.complete(Unit)
        releaseSend.await()
        val accepted = leaseIsActive() &&
            expectedOwnerGeneration == currentOwnerGeneration &&
            expectedConnectionGeneration == currentConnectionGeneration &&
            state.value == ConnectionState.AUTHENTICATED
        if (accepted) sentPayloads += payload
        return accepted
    }

    fun replaceAuthenticatedOwner(ownerGeneration: Long, connectionGeneration: Long) {
        currentOwnerGeneration = ownerGeneration
        currentConnectionGeneration = connectionGeneration
        state.value = ConnectionState.AUTHENTICATED
    }
}
