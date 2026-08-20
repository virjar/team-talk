package com.virjar.tk.client

import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.ResponsePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test
    fun `business quiesce closes actual wire admission while raw logout can still send`() = runTest {
        val transport = BarrierRpcRequestTransport()
        // Keep the response collector on the same virtual scheduler as the RPC timeout. A real
        // Dispatchers.Default collector uses wall-clock time; mixing it with runTest would let the
        // caller jump straight to 10s before the just-emitted response gets CPU, producing a fake
        // 504 even though the raw logout admission and response correlation are both valid.
        val raw = RpcClient(transport, StandardTestDispatcher(testScheduler))
        raw.start()
        val lifecycle = SessionLifecycleGate()
        val outbound = SessionOutboundLease()
        val business = SessionBusinessRpcInvoker(raw, lifecycle, outbound)

        val oldBusiness = async {
            business.invoke("conversation", 7, byteArrayOf(1))
        }
        transport.sendEntered.await()

        // Account A crossed RPC registration, but quiesce retires the admission callback that is
        // rechecked where the transport would actually put bytes on the wire.
        lifecycle.beginQuiesce(SessionEndReason.USER_LOGOUT, outbound::retire)
        transport.releaseSend.complete(Unit)

        assertFailsWith<CancellationException> { oldBusiness.await() }
        assertTrue(transport.sentPayloads.isEmpty())

        // The same RpcClient is reachable only through ClientSession's sealed retirement owner;
        // its raw logout request deliberately does not inherit the retired business admission.
        val logout = async { raw.invoke("auth", 3, null) }
        val sentLogout = transport.logoutSent.await()
        transport.publishResponse(sentLogout.requestId)
        assertEquals(0, logout.await().status)
        assertEquals(listOf("auth"), transport.sentPayloads.map(InvokePayload::serviceId))
        raw.stop()
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
        sendAdmission: WireSendAdmission,
        payload: InvokePayload,
    ): Boolean = awaitCancellation()
}

private class BarrierRpcRequestTransport : RpcRequestTransport {
    override val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
    private val packets = MutableSharedFlow<RoutedPacket>()
    override val routedPackets: Flow<RoutedPacket> = packets
    override val transportDisconnectEpoch: StateFlow<Long> = MutableStateFlow(0L)

    @Volatile
    override var currentOwnerGeneration: Long = 1L
        private set

    @Volatile
    override var currentConnectionGeneration: Long = 11L
        private set

    val sendEntered = CompletableDeferred<Unit>()
    val releaseSend = CompletableDeferred<Unit>()
    val logoutSent = CompletableDeferred<InvokePayload>()
    val sentPayloads = mutableListOf<InvokePayload>()

    override suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        payload: InvokePayload,
    ): Boolean {
        sendEntered.complete(Unit)
        releaseSend.await()
        val accepted = sendAdmission.use {
            expectedOwnerGeneration == currentOwnerGeneration &&
                expectedConnectionGeneration == currentConnectionGeneration &&
                state.value == ConnectionState.AUTHENTICATED
        }
        if (accepted) {
            sentPayloads += payload
            if (payload.serviceId == "auth") logoutSent.complete(payload)
        }
        return accepted
    }

    suspend fun publishResponse(requestId: Int) {
        packets.emit(
            RoutedPacket(currentConnectionGeneration, ResponsePayload(requestId, 0, null)),
        )
    }

    fun replaceAuthenticatedOwner(ownerGeneration: Long, connectionGeneration: Long) {
        currentOwnerGeneration = ownerGeneration
        currentConnectionGeneration = connectionGeneration
        state.value = ConnectionState.AUTHENTICATED
    }
}
