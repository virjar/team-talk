package com.virjar.tk.server.protocol.dispatcher

import com.virjar.tk.server.domain.chat.GroupCreationConflictException
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandExpiredException
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentCustodyConflictException
import com.virjar.tk.server.domain.document.DocumentHierarchyConflictException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentRevisionConflictException
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import com.virjar.tk.server.protocol.rpc.RpcStubRegistry
import com.virjar.tk.protocol.rpc.RpcStub
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RpcDispatcherConflictTest {
    @Test
    fun `typed domain statuses remain distinct from ordinary validation`() = runTest {
        val registry = RpcStubRegistry().apply {
            register("document-conflict") {
                throwingStub { throw DocumentRevisionConflictException() }
            }
            register("document-custody-conflict") {
                throwingStub { throw DocumentCustodyConflictException() }
            }
            register("document-hierarchy-conflict") {
                throwingStub { throw DocumentHierarchyConflictException() }
            }
            register("ordinary-validation") {
                throwingStub { throw IllegalArgumentException("参数非法") }
            }
            register("document-not-found") {
                throwingStub { throw DocumentNotFoundException("文档不存在") }
            }
            register("document-permission-denied") {
                throwingStub { throw DocumentAccessDeniedException("没有文档空间权限") }
            }
            register("group-creation-conflict") {
                throwingStub { throw GroupCreationConflictException() }
            }
            register("reliable-command-conflict") {
                throwingStub { throw ReliableCommandConflictException("操作标识冲突") }
            }
            register("reliable-command-expired") {
                throwingStub { throw ReliableCommandExpiredException("操作已过期") }
            }
            register("reliable-command-capacity") {
                throwingStub { throw ReliableCommandCapacityException("可靠窗口已满") }
            }
            register("permission-denied") {
                throwingStub { throw ChatAccessDeniedException("需要管理员权限") }
            }
            register("internal-invariant") {
                throwingStub { throw IllegalStateException("内部投影断链") }
            }
        }
        val dispatcher = RpcDispatcher(registry)

        val conflict = dispatcher.dispatchForTest("document-conflict")
        val custodyConflict = dispatcher.dispatchForTest("document-custody-conflict")
        val hierarchyConflict = dispatcher.dispatchForTest("document-hierarchy-conflict")
        val validation = dispatcher.dispatchForTest("ordinary-validation")
        val notFound = dispatcher.dispatchForTest("document-not-found")
        val documentPermissionDenied = dispatcher.dispatchForTest("document-permission-denied")
        val groupConflict = dispatcher.dispatchForTest("group-creation-conflict")
        val reliableConflict = dispatcher.dispatchForTest("reliable-command-conflict")
        val reliableExpired = dispatcher.dispatchForTest("reliable-command-expired")
        val reliableCapacity = dispatcher.dispatchForTest("reliable-command-capacity")
        val permissionDenied = dispatcher.dispatchForTest("permission-denied")
        val internalInvariant = dispatcher.dispatchForTest("internal-invariant")

        assertEquals(409, conflict.status)
        assertEquals(DocumentRevisionConflictException.MESSAGE, conflict.payload?.decodeToString())
        assertEquals(409, custodyConflict.status)
        assertEquals(DocumentCustodyConflictException.MESSAGE, custodyConflict.payload?.decodeToString())
        assertEquals(409, hierarchyConflict.status)
        assertEquals(DocumentHierarchyConflictException.MESSAGE, hierarchyConflict.payload?.decodeToString())
        assertEquals(400, validation.status)
        assertEquals("参数非法", validation.payload?.decodeToString())
        assertEquals(404, notFound.status)
        assertEquals("文档不存在", notFound.payload?.decodeToString())
        assertEquals(403, documentPermissionDenied.status)
        assertEquals("没有文档空间权限", documentPermissionDenied.payload?.decodeToString())
        assertEquals(409, groupConflict.status)
        assertEquals(GroupCreationConflictException.MESSAGE, groupConflict.payload?.decodeToString())
        assertEquals(409, reliableConflict.status)
        assertEquals("操作标识冲突", reliableConflict.payload?.decodeToString())
        assertEquals(410, reliableExpired.status)
        assertEquals("操作已过期", reliableExpired.payload?.decodeToString())
        assertEquals(429, reliableCapacity.status)
        assertEquals("可靠窗口已满", reliableCapacity.payload?.decodeToString())
        assertEquals(403, permissionDenied.status)
        assertEquals("需要管理员权限", permissionDenied.payload?.decodeToString())
        assertEquals(500, internalInvariant.status)
        assertEquals("服务器内部错误", internalInvariant.payload?.decodeToString())
    }

    @Test
    fun `authoritative result encoding failure is an internal error rather than client validation`() = runTest {
        val registry = RpcStubRegistry().apply {
            register("oversized-result") {
                returningStub(ByteArray(MAX_RPC_ENVELOPE_BODY_BYTES + 1))
            }
        }

        val response = RpcDispatcher(registry).dispatchForTest("oversized-result")

        assertEquals(500, response.status)
        assertEquals("服务器内部错误", response.payload?.decodeToString())
    }

    @Test
    fun `dispatcher rethrows the exact owner cancellation`() = runTest {
        val cancellation = CancellationException("session retired")
        val registry = RpcStubRegistry().apply {
            register("cancelled") { throwingStub { throw cancellation } }
        }

        var propagated: CancellationException? = null
        try {
            RpcDispatcher(registry).dispatchForTest("cancelled")
        } catch (cancelled: CancellationException) {
            propagated = cancelled
        }

        assertSame(cancellation, propagated)
    }

    @Test
    fun `negotiated version reaches the request implementation and unavailable protocol rejects before it`() = runTest {
        val selected = ProtocolVersion(ProtocolVersions.MAJOR, 23)
        var receivedVersion: ProtocolVersion? = null
        var unavailableMethodEntered = false
        val registry = RpcStubRegistry().apply {
            register("version-context") { session ->
                receivedVersion = session.protocolVersion
                returningStub(byteArrayOf(session.protocolVersion.minor.toByte()))
            }
            register(UserRpcContract.SERVICE) {
                unavailableMethodEntered = true
                returningStub(byteArrayOf())
            }
        }
        val dispatcher = RpcDispatcher(registry)
        val result = dispatcher.dispatch(
            uid = "u1", deviceId = "device-1", deviceCredentialEpoch = 1L, sessionId = "session-1",
            invoke = InvokePayload(1, "version-context", 1, null),
            protocolVersion = selected,
        )
        assertEquals(0, result.status)
        assertEquals(selected, receivedVersion)
        assertEquals(23, result.payload!!.single().toInt())

        val denied = dispatcher.dispatch(
            uid = "u1", deviceId = "device-1", deviceCredentialEpoch = 1L, sessionId = "session-1",
            invoke = InvokePayload(2, UserRpcContract.SERVICE, UserRpcContract.M_GET_PROFILE, null),
            protocolVersion = ProtocolVersion(ProtocolVersions.MAJOR xor 1, 0),
        )
        assertEquals(426, denied.status)
        assertEquals(false, unavailableMethodEntered)
    }

    private fun throwingStub(block: suspend () -> Nothing): RpcStub = object : RpcStub("u1") {
        override suspend fun dispatch(methodId: Int, payload: ByteArray?): ByteArray = block()
    }

    private fun returningStub(result: ByteArray): RpcStub = object : RpcStub("u1") {
        override suspend fun dispatch(methodId: Int, payload: ByteArray?): ByteArray = result
    }

    private suspend fun RpcDispatcher.dispatchForTest(serviceId: String) = dispatch(
        uid = "u1",
        deviceId = "device-1",
        deviceCredentialEpoch = 1,
        sessionId = "session-1",
        invoke = InvokePayload(1, serviceId, 1, null),
    )
}
