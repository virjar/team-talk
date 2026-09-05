package com.virjar.tk.shared.client

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

        // 在账号 A 的待处理请求已注册但 EventLoop 发送决策尚未做出之前让它退场。
        // 可复用的 transport 随即成为一条已认证的账号 B 连接。
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
        // 让响应收集器与 RPC 超时使用同一个虚拟调度器。真实的
        // Dispatchers.Default 收集器使用挂钟时间；把它与 runTest 混用会让
        // 调用方在刚发出的响应获得 CPU 之前直接跳到 10 秒后，从而产生一个虚假的
        // 504，尽管原始 logout 准入与响应关联都是有效的。
        val raw = RpcClient(transport, StandardTestDispatcher(testScheduler))
        raw.start()
        val lifecycle = SessionLifecycleGate()
        val outbound = SessionOutboundLease()
        val business = SessionBusinessRpcInvoker(raw, lifecycle, outbound)

        val oldBusiness = async {
            business.invoke("conversation", 7, byteArrayOf(1))
        }
        transport.sendEntered.await()

        // 账号 A 已经通过 RPC 注册，但 quiesce 会让准入回调退场，
        // 该回调在 transport 真正把字节放到线上之前会被重新检查。
        lifecycle.beginQuiesce(SessionEndReason.USER_LOGOUT, outbound::retire)
        transport.releaseSend.complete(Unit)

        assertFailsWith<CancellationException> { oldBusiness.await() }
        assertTrue(transport.sentPayloads.isEmpty())

        // 同一个 RpcClient 只能通过 ClientSession 的封闭退场 owner 触达；
        // 它的原始 logout 请求刻意不继承已退场的业务准入。
        val logout = async { raw.invoke("auth", 3, null) }
        val sentLogout = transport.logoutSent.await()
        transport.publishResponse(sentLogout.requestId)
        assertEquals(0, logout.await().status)
        assertEquals(listOf("auth"), transport.sentPayloads.map(InvokePayload::serviceId))
        raw.stop()
    }

    @Test
    fun `checkpoint retirement closes the registered synchronization RPC before wire send`() = runTest {
        val transport = BarrierRpcRequestTransport().apply { beginSynchronization() }
        val raw = RpcClient(transport, StandardTestDispatcher(testScheduler))
        raw.start()
        val admission = SessionOutboundLease()
        val synchronization = SynchronizationRpcInvoker(raw, admission)

        val checkpoint = async {
            synchronization.invoke(SyncRpcContract.SERVICE, SyncRpcContract.M_BEGIN_CHECKPOINT, null)
        }
        transport.sendEntered.await()
        admission.retire()
        transport.releaseSend.complete(Unit)

        assertFailsWith<CancellationException> { checkpoint.await() }
        assertTrue(transport.sentPayloads.isEmpty())
        raw.stop()
    }

    @Test
    fun `synchronizing connection admits only the dedicated sync RPC capability`() = runTest {
        val transport = BarrierRpcRequestTransport().apply { beginSynchronization() }
        val raw = RpcClient(transport, StandardTestDispatcher(testScheduler))
        raw.start()
        val synchronization = SynchronizationRpcInvoker(raw, SessionOutboundLease())

        val checkpoint = async {
            synchronization.invoke(SyncRpcContract.SERVICE, SyncRpcContract.M_BEGIN_CHECKPOINT, null)
        }
        transport.sendEntered.await()
        transport.releaseSend.complete(Unit)
        runCurrent()
        val sent = transport.sentPayloads.single()
        transport.publishResponse(sent.requestId)
        runCurrent()

        assertEquals(0, checkpoint.await().status)
        assertEquals(SyncRpcContract.SERVICE, sent.serviceId)
        assertFailsWith<TransportUnavailableException> {
            raw.invoke(SyncRpcContract.SERVICE, SyncRpcContract.M_BEGIN_CHECKPOINT, null)
        }
        assertFailsWith<IllegalArgumentException> {
            synchronization.invoke("conversation", 1, null)
        }
        assertEquals(1, transport.sentPayloads.size)
        raw.stop()
    }

    @Test
    fun `pending request registry rejects overload and releases capacity on cancellation`() = runTest {
        val transport = BarrierRpcRequestTransport()
        val rpc = RpcClient(
            transport = transport,
            lifecycleDispatcher = StandardTestDispatcher(testScheduler),
            maxPendingRequests = 1,
        )
        rpc.start()
        val first = async { rpc.invoke("conversation", 1, null) }
        transport.sendEntered.await()

        assertFailsWith<RpcClientCapacityExceededException> {
            rpc.invoke("conversation", 2, null)
        }

        first.cancel()
        transport.releaseSend.complete(Unit)
        assertFailsWith<CancellationException> { first.await() }
        rpc.stop()
    }

    @Test
    fun `disconnected preflight maps to Network without attempting a send`() = runTest {
        val transport = BarrierRpcRequestTransport().apply { disconnectWithoutEpoch() }
        val rpc = RpcClient(transport, StandardTestDispatcher(testScheduler))
        rpc.start()

        val result = outcome { rpc.invoke("organization", 1, null) }

        assertEquals(AppError.Network, assertIs<Outcome.Failure>(result).error)
        assertTrue(!transport.sendEntered.isCompleted)
        rpc.stop()
    }

    @Test
    fun `disconnect before transport send returns throws typed transport failure`() = runTest {
        val transport = BarrierRpcRequestTransport()
        val rpc = RpcClient(transport, StandardTestDispatcher(testScheduler))
        rpc.start()
        val invocation = async { runCatching { rpc.invoke("conversation", 7, null) } }
        transport.sendEntered.await()

        transport.disconnectWithoutEpoch()
        transport.releaseSend.complete(Unit)

        assertIs<TransportUnavailableException>(invocation.await().exceptionOrNull())
        rpc.stop()
    }

    @Test
    fun `disconnect while awaiting response throws typed transport failure`() = runTest {
        val transport = BarrierRpcRequestTransport()
        val rpc = RpcClient(transport, StandardTestDispatcher(testScheduler))
        rpc.start()
        val invocation = async { runCatching { rpc.invoke("conversation", 7, null) } }
        transport.sendEntered.await()
        transport.releaseSend.complete(Unit)
        runCurrent()
        assertEquals(1, transport.sentPayloads.size)

        transport.publishDisconnect()
        runCurrent()

        assertIs<TransportUnavailableException>(invocation.await().exceptionOrNull())
        rpc.stop()
    }
}

private class NeverExecutingRpcRequestTransport : RpcRequestTransport {
    override val negotiatedProtocolVersion = com.virjar.tk.protocol.ProtocolVersions.CURRENT
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
    override val negotiatedProtocolVersion = com.virjar.tk.protocol.ProtocolVersions.CURRENT
    override val state = MutableStateFlow(ConnectionState.AUTHENTICATED)
    private val packets = MutableSharedFlow<RoutedPacket>()
    override val routedPackets: Flow<RoutedPacket> = packets
    private val disconnectEpoch = MutableStateFlow(0L)
    override val transportDisconnectEpoch: StateFlow<Long> = disconnectEpoch

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
                state.value != ConnectionState.DISCONNECTED
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

    fun beginSynchronization() {
        state.value = ConnectionState.SYNCHRONIZING
    }

    fun disconnectWithoutEpoch() {
        state.value = ConnectionState.DISCONNECTED
    }

    fun publishDisconnect() {
        state.value = ConnectionState.DISCONNECTED
        disconnectEpoch.value += 1L
    }
}
