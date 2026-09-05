package com.virjar.tk.server.domain.presence

import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.event.TransientEventPublisher
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PresenceServiceTest {
    @Test
    fun `broadcast preserves registry epoch revision and offline occurrence time for every friend`() = runTest {
        val emitted = mutableListOf<Emission>()
        val service = PresenceService(
            contacts = contactRepository(linkedSetOf("bob", "carol")),
            events = object : TransientEventPublisher {
                override suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: IProto) {
                    emitted += Emission(uid, notifyType, payload)
                }
            },
        )
        val transition = PresenceTransition(
            uid = "alice",
            online = false,
            occurredAt = 987_654L,
            serverEpoch = EPOCH,
            revision = 17L,
        )

        service.broadcast(transition)

        assertEquals(listOf("bob", "carol"), emitted.map(Emission::targetUid))
        emitted.forEach { event ->
            assertEquals(NotifyType.PRESENCE, event.notifyType)
            assertEquals(
                PresencePayload(
                    uid = "alice",
                    status = PresencePayload.STATUS_OFFLINE,
                    lastSeenAt = transition.occurredAt,
                    serverEpoch = EPOCH,
                    revision = transition.revision,
                ),
                assertIs<PresencePayload>(event.payload),
            )
        }
    }

    @Test
    fun `online broadcast uses zero last seen without regenerating version metadata`() = runTest {
        var emitted: PresencePayload? = null
        val service = PresenceService(
            contacts = contactRepository(setOf("bob")),
            events = object : TransientEventPublisher {
                override suspend fun emitTransient(uid: String, notifyType: NotifyType, payload: IProto) {
                    emitted = assertIs<PresencePayload>(payload)
                }
            },
        )

        service.broadcast(
            PresenceTransition(
                uid = "alice",
                online = true,
                occurredAt = 123_456L,
                serverEpoch = EPOCH,
                revision = 18L,
            ),
        )

        assertEquals(PresencePayload.STATUS_ONLINE, emitted?.status)
        assertEquals(0L, emitted?.lastSeenAt)
        assertEquals(EPOCH, emitted?.serverEpoch)
        assertEquals(18L, emitted?.revision)
    }

    private data class Emission(val targetUid: String, val notifyType: NotifyType, val payload: IProto)

    private companion object {
        const val EPOCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

        @Suppress("UNCHECKED_CAST")
        fun contactRepository(friendUids: Set<String>): ContactRepository {
            val type = ContactRepository::class.java
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.name) {
                    "listFriendUids" -> friendUids
                    "toString" -> "PresenceContactRepository"
                    else -> error("Unexpected ContactRepository call: ${method.name}")
                }
            } as ContactRepository
        }
    }
}
