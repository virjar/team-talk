package com.virjar.tk.server.integration

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.rpc.gen.DeviceRpcContract
import com.virjar.tk.server.domain.auth.TokenInfo
import com.virjar.tk.server.infra.db.*
import com.virjar.tk.server.infra.push.*
import com.virjar.tk.server.infra.sync.LiveEventSink
import com.virjar.tk.server.infra.sync.SyncEventDispatcher
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.server.protocol.rpc.DeviceRpcImpl
import com.virjar.tk.server.protocol.rpc.RpcStubRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.*

class XiaomiPushIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
        private val FP = "a".repeat(64)
        private const val PACKAGE = "com.example.teamtalk"
    }
    private val ctx get() = ext.env
    private var now = System.currentTimeMillis()
    private val configuration = XiaomiPushConfiguration(true, "fixture-secret", PACKAGE, "channel", "template", "TeamTalk")

    @BeforeEach fun resetRegistrations() {
        transaction(ctx.database) { XiaomiPushRegistrations.deleteAll() }
    }

    @Test fun `registered RPC uses authenticated installation preserves refresh and cascades revocation`() = runTest {
        val user = user()
        val service = service()
        val registry = RpcStubRegistry().apply {
            register(DeviceRpcContract.SERVICE) { session ->
                DeviceRpcImpl(session.uid, ctx.deviceRepo, ctx.authService,
                    session.deviceId, session.deviceCredentialEpoch, service)
            }
        }
        val dispatcher = RpcDispatcher(registry)
        suspend fun register(principal: TokenInfo = user.principal, version: ProtocolVersion = ProtocolVersion(0, 2),
            registration: String = "registration-one", fingerprint: String = FP) = dispatcher.dispatch(
            principal.uid, principal.deviceId, principal.deviceCredentialEpoch, "xiaomi-fixture",
            InvokePayload(1, DeviceRpcContract.SERVICE, DeviceRpcContract.M_SET_XIAOMI_PUSH_REGISTRATION,
                DeviceRpcContract.encodeSetXiaomiPushRegistration(registration, PACKAGE, fingerprint)), version)
        assertNotEquals(0, register(version = ProtocolVersion(0, 1)).status)
        assertTrue(rows().isEmpty())
        assertNotEquals(0, register(fingerprint = "not-an-owner").status)
        val accepted = register()
        assertEquals(0, accepted.status)
        assertTrue(ProtoCodec.withPayload(accepted.payload) { readBoolean() })
        val first = rows().single()
        val refresh = ctx.authService.handleAuth(AuthRequestPayload(authType = 2, refreshToken = user.login.refreshToken,
            deviceId = user.principal.deviceId, deviceFlag = 1, correlationId = id(), connectionGeneration = 2))
        assertEquals(0, refresh.code)
        val next = assertNotNull(ctx.accessTokenValidator.validateAccessToken(assertNotNull(refresh.accessToken)))
        assertTrue(next.deviceCredentialEpoch > user.principal.deviceCredentialEpoch)
        assertEquals(first[XiaomiPushRegistrations.generation], rows().single()[XiaomiPushRegistrations.generation])
        assertFalse(ProtoCodec.withPayload(register().payload) { readBoolean() }, "old authenticated epoch cannot replace registration")
        assertTrue(ProtoCodec.withPayload(register(next).payload) { readBoolean() })
        assertEquals(first[XiaomiPushRegistrations.generation], rows().single()[XiaomiPushRegistrations.generation])
        ctx.authService.revokeDevice(next.uid, next.deviceId)
        assertTrue(rows().isEmpty(), "credential deletion cascades pending notifications")
        assertFalse(ProtoCodec.withPayload(register(next).payload) { readBoolean() })
    }

    @Test fun `coalesced durable hints survive restart and provider retry uses stable job key`() = runTest {
        val receiver = user(); val sender = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, receiver.principal.uid)
        val attempts = mutableListOf<XiaomiPushNotification>()
        val failing = service { _, notification ->
            attempts += notification
            XiaomiPushDeliveryResult(false, "PROVIDER_BUSY")
        }
        register(failing, receiver)
        send(sender, chat.chatId); send(sender, chat.chatId)
        dispatch(failing, receiver.principal.uid)
        assertEquals(1, rows().size)
        assertEquals(1, failing.drainDue())
        assertEquals(0, failing.drainDue())
        val pending = rows().single()
        assertEquals(1, pending[XiaomiPushRegistrations.attempts])
        assertEquals("PROVIDER_BUSY", pending[XiaomiPushRegistrations.lastFailure])
        now += 5_000
        val restarted = service { _, notification -> attempts += notification; XiaomiPushDeliveryResult(true) }
        assertEquals(1, restarted.drainDue())
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].jobKey, attempts[1].jobKey)
        assertEquals(receiver.principal.uid, attempts.last().uid)
        assertEquals(chat.chatId, attempts.last().chatId)
        assertEquals(FP, attempts.last().deploymentFingerprint)
        assertEquals("fixture-dataset", attempts.last().datasetId)
        assertEquals(0, restarted.drainDue())
        assertEquals(rows().single()[XiaomiPushRegistrations.pendingEventId], rows().single()[XiaomiPushRegistrations.deliveredEventId])

        val newerSender = ctx.registerUser()
        val newerChat = ctx.chatService.createPersonalChat(newerSender, receiver.principal.uid)
        send(sender, chat.chatId)
        val newerSeq = send(newerSender, newerChat.chatId)
        dispatch(restarted, receiver.principal.uid)
        ctx.conversationService.markRead(receiver.principal.uid, newerChat.chatId, newerSeq)
        restarted.drainDue()
        assertEquals(chat.chatId, attempts.last().chatId,
            "reading the latest chat must not consume an older chat's pending unread hint")

        val leftGroup = ctx.chatService.createGroup(id(), "Retired push scope", null, sender, listOf(receiver.principal.uid))
        val leftSeq = send(sender, leftGroup.chatId)
        ctx.chatService.leaveGroup(receiver.principal.uid, leftGroup.chatId)
        ctx.conversationService.deleteConversation(receiver.principal.uid, newerChat.chatId)
        // Model an unavailable provider retaining old scopes: two actual retired conversations and
        // the remaining historical IDs no longer present in the current readable conversation set.
        val retired = mutableMapOf(leftGroup.chatId to leftSeq, newerChat.chatId to newerSeq)
        repeat(com.virjar.tk.protocol.model.ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER - retired.size) {
            retired[UUID.nameUUIDFromBytes("retired-push-$it".encodeToByteArray()).toString()] = 1L
        }
        transaction(ctx.database) {
            XiaomiPushRegistrations.update({ XiaomiPushRegistrations.refreshTokenHash eq
                pending[XiaomiPushRegistrations.refreshTokenHash] }) {
                it[pendingChats] = Json.encodeToString(retired)
            }
        }
        send(sender, chat.chatId)
        dispatch(restarted, receiver.principal.uid)
        assertEquals(setOf(chat.chatId), Json.decodeFromString<Map<String, Long>>(
            rows().single()[XiaomiPushRegistrations.pendingChats]).keys)
        assertTrue(transaction(ctx.database) {
            SyncEvents.selectAll().where { SyncEvents.uid eq receiver.principal.uid }
                .all { it[SyncEvents.dispatchedAt] != null }
        }, "retired pending scopes must not block durable sync dispatch")
        val deliveriesBefore = attempts.size
        assertEquals(1, restarted.drainDue())
        assertEquals(deliveriesBefore + 1, attempts.size)
        assertEquals(chat.chatId, attempts.last().chatId)
    }

    @Test fun `sync dispatch receipt and OEM queue roll back together before retry`() = runTest {
        val receiver = user(); val sender = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, receiver.principal.uid)
        val service = service(); register(service, receiver)
        send(sender, chat.chatId)
        val broken = SyncEventDispatcher(ctx.database, LiveEventSink { _, _ -> }, onDispatched = { uid, event, type, bytes ->
            service.recordDispatchedEvent(uid, event, type, bytes)
            if (type == NotifyType.MESSAGE_RECV.code) error("fixture-before-commit")
        })
        try {
            assertFailsWith<IllegalStateException> { broken.dispatchPendingForUid(receiver.principal.uid) }
        } finally { broken.close() }
        assertEquals(0L, rows().single()[XiaomiPushRegistrations.pendingEventId])
        assertTrue(transaction(ctx.database) {
            SyncEvents.selectAll().where { (SyncEvents.uid eq receiver.principal.uid) and
                (SyncEvents.eventType eq NotifyType.MESSAGE_RECV.code) }.all { it[SyncEvents.dispatchedAt] == null }
        })
        dispatch(service, receiver.principal.uid)
        assertTrue(rows().single()[XiaomiPushRegistrations.pendingEventId] > 0)
        assertEquals(1, service.drainDue())
    }

    @Test fun `current read mute membership and revoke facts suppress queued hints and own messages never queue`() = runTest {
        val receiver = user(); val sender = ctx.registerUser()
        val chat = ctx.chatService.createGroup(id(), "Push fixture", null, sender, listOf(receiver.principal.uid))
        var delivered = 0
        val service = service { _, _ -> delivered++; XiaomiPushDeliveryResult(true) }
        register(service, receiver)
        send(receiver.principal.uid, chat.chatId)
        dispatch(service, receiver.principal.uid)
        assertEquals(0, service.drainDue())

        val read = send(sender, chat.chatId); dispatch(service, receiver.principal.uid)
        ctx.conversationService.markRead(receiver.principal.uid, chat.chatId, read)
        service.drainDue(); assertEquals(0, delivered)

        send(sender, chat.chatId); dispatch(service, receiver.principal.uid)
        ctx.conversationService.setMute(receiver.principal.uid, chat.chatId, true)
        service.drainDue(); assertEquals(0, delivered)
        send(sender, chat.chatId); dispatch(service, receiver.principal.uid)
        assertEquals(0, service.drainDue(), "muted receipt does not create a pending wake")
        ctx.conversationService.setMute(receiver.principal.uid, chat.chatId, false)

        val revoked = send(sender, chat.chatId); dispatch(service, receiver.principal.uid)
        ctx.messageService.revokeMessage(sender, chat.chatId, revoked)
        service.drainDue(); assertEquals(0, delivered)
        dispatch(service, receiver.principal.uid)
        assertEquals(0, service.drainDue(), "revoke event is not a new-message notification")

        send(sender, chat.chatId); dispatch(service, receiver.principal.uid)
        ctx.chatService.leaveGroup(receiver.principal.uid, chat.chatId)
        service.drainDue(); assertEquals(0, delivered)
    }

    @Test fun `provider results preserve newer work and replacement registrations while retaining failure backoff`() = runTest {
        val receiver = user(); val sender = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(sender, receiver.principal.uid)
        val notifications = mutableListOf<XiaomiPushNotification>()
        lateinit var service: XiaomiPushNotifications
        service = service { _, notification ->
            notifications += notification
            if (notifications.size == 1) {
                send(sender, chat.chatId)
                dispatch(service, receiver.principal.uid)
            }
            XiaomiPushDeliveryResult(true)
        }
        register(service, receiver)
        send(sender, chat.chatId); dispatch(service, receiver.principal.uid)
        service.drainDue()
        assertTrue(rows().single()[XiaomiPushRegistrations.pendingEventId] > rows().single()[XiaomiPushRegistrations.deliveredEventId])
        service.drainDue()
        assertEquals(2, notifications.size)
        assertNotEquals(notifications[0].jobKey, notifications[1].jobKey)

        val replacing = service { _, _ ->
            register(service, receiver, "replacement-registration")
            XiaomiPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
        }
        send(sender, chat.chatId); dispatch(replacing, receiver.principal.uid)
        replacing.drainDue()
        assertEquals("replacement-registration", rows().single()[XiaomiPushRegistrations.registrationId])

        var failureCalls = 0
        var newerMessageSeq = 0L
        lateinit var failing: XiaomiPushNotifications
        failing = service { _, _ ->
            failureCalls++
            newerMessageSeq = send(sender, chat.chatId)
            dispatch(failing, receiver.principal.uid)
            XiaomiPushDeliveryResult(false, "PROVIDER_RATE_LIMITED")
        }
        send(sender, chat.chatId); dispatch(failing, receiver.principal.uid)
        val beforeFailure = rows().single()[XiaomiPushRegistrations.pendingEventId]
        assertEquals(1, failing.drainDue())
        val failed = rows().single()
        assertTrue(failed[XiaomiPushRegistrations.pendingEventId] > beforeFailure)
        assertTrue(failed[XiaomiPushRegistrations.deliveredEventId] < failed[XiaomiPushRegistrations.pendingEventId])
        assertEquals(newerMessageSeq, Json.decodeFromString<Map<String, Long>>(
            failed[XiaomiPushRegistrations.pendingChats])[chat.chatId])
        assertEquals(1, failed[XiaomiPushRegistrations.attempts])
        assertEquals("PROVIDER_RATE_LIMITED", failed[XiaomiPushRegistrations.lastFailure])
        assertEquals(now + 5_000L, failed[XiaomiPushRegistrations.nextAttemptAt])
        assertEquals(0, failing.drainDue())
        assertEquals(1, failureCalls, "new incoming messages must not bypass the provider backoff")

        now += 5_000L
        val invalid = service { _, _ ->
            send(sender, chat.chatId)
            dispatch(failing, receiver.principal.uid)
            XiaomiPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
        }
        assertEquals(1, invalid.drainDue())
        assertTrue(rows().isEmpty(), "66007 retires this registration even when another message arrived in flight")
    }

    @Test fun `unregister disabled profile package mismatch desktop and account switch keep exact binding`() = runTest {
        val first = user(); val second = user(); val desktop = user(deviceFlag = 2)
        val service = service()
        assertFalse(register(service, desktop))
        assertTrue(register(service, first))
        assertTrue(register(service, second))
        val hash = rows().single()[XiaomiPushRegistrations.refreshTokenHash]
        assertEquals(second.principal.uid, transaction(ctx.database) {
            Credentials.selectAll().where { Credentials.tokenHash eq hash }.single()[Credentials.uid]
        })
        assertFalse(service.register(first.principal.uid, first.principal.deviceId, first.principal.deviceCredentialEpoch,
            "another-registration", "com.other.app", FP))
        assertEquals(1, rows().size, "another account cannot remove this binding")
        assertFalse(register(service, second, "")); assertTrue(rows().isEmpty())
        assertFalse(register(XiaomiPushNotifications(ctx.database, XiaomiPushConfiguration(), "fixture-dataset", ctx.messageStore), first))
        assertTrue(rows().isEmpty())
    }

    private fun service(send: suspend (XiaomiPushConfiguration, XiaomiPushNotification) -> XiaomiPushDeliveryResult = { _, _ -> XiaomiPushDeliveryResult(true) }) =
        XiaomiPushNotifications(ctx.database, configuration, "fixture-dataset", ctx.messageStore, { now }, send)
    private fun register(service: XiaomiPushNotifications, user: Login, token: String = "registration-one") =
        service.register(user.principal.uid, user.principal.deviceId, user.principal.deviceCredentialEpoch, token, PACKAGE, FP)
    private fun rows() = transaction(ctx.database) { XiaomiPushRegistrations.selectAll().toList() }
    private suspend fun dispatch(service: XiaomiPushNotifications, uid: String) {
        val dispatcher = SyncEventDispatcher(ctx.database, LiveEventSink { _, _ -> }, onDispatched = service::recordDispatchedEvent)
        try { dispatcher.dispatchPendingForUid(uid) } finally { dispatcher.close() }
    }
    private suspend fun send(uid: String, chatId: String) = ctx.messageService.sendMessage(uid, Message(
        chatId, id(), senderUid = uid, messageType = MessageType.RICH_TEXT.code,
        timestamp = System.currentTimeMillis(), body = buildRichTextBody("Private fixture text must never enter OEM payload")))
    private class Login(val principal: TokenInfo, val login: AuthResponsePayload)
    private suspend fun user(deviceFlag: Int = 1): Login {
        val username = uniqueUsername("xiaomi-push")
        ctx.registerUser(username, "pass123")
        val response = ctx.authService.handleAuth(AuthRequestPayload(authType = 0, username = username, password = "pass123",
            deviceId = id(), deviceFlag = deviceFlag, correlationId = id(), connectionGeneration = 1))
        assertEquals(0, response.code)
        return Login(assertNotNull(ctx.accessTokenValidator.validateAccessToken(assertNotNull(response.accessToken))), response)
    }
    private fun id() = UUID.randomUUID().toString()
}
