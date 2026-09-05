package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfflineSessionBootstrapTest {
    @Test
    fun `persisted owner publishes its local session before remote transport starts`() = runBlocking {
        val identity = DeploymentIdentity.from(
            tcpHost = UNREACHABLE_HOST,
            tcpPort = 5100,
            serverUrl = "https://offline.test.example",
        )
        val user = UserSession().apply {
            restorePersistedLogin(OWNER_UID, "persisted-refresh", TEST_SYNC_DATASET_ID)
        }
        val expectedCache = FakeLocalCache().apply {
            upsertConversation(
                Conversation(
                    chatId = "cached-chat",
                    chatType = 1,
                    lastSeq = 9L,
                    readSeq = 4L,
                    unreadCount = 5,
                ),
            )
        }
        val client = ImClient()
        var session: ClientSession? = null
        try {
            val prepared = client.prepareAuthentication(
                uid = OWNER_UID,
                token = "persisted-refresh",
                deviceId = "offline-test-device",
                deviceName = "Offline test",
                host = UNREACHABLE_HOST,
                port = 5100,
            )
            withTimeout(5_000L) { client.awaitTransportOwnerStart() }
            assertEquals(0L, client.currentConnectionGeneration)
            assertEquals(ConnectionState.DISCONNECTED, client.state.value)

            session = createSession(
                imClient = client,
                userSession = user,
                deploymentIdentity = identity,
                createCache = { openedIdentity, openedDatasetId, openedUid ->
                    assertEquals(identity, openedIdentity)
                    assertEquals(TEST_SYNC_DATASET_ID, openedDatasetId)
                    assertEquals(OWNER_UID, openedUid)
                    expectedCache
                },
                deviceId = "offline-test-device",
                logUploadEnabled = false,
            )

            assertEquals(OWNER_UID, session.ownerUid)
            assertSame(expectedCache, session.localCache)
            assertEquals("cached-chat", session.localCache.getConversations().single().chatId)
            assertFalse(session.connectionState.value == ConnectionState.AUTHENTICATED)
            assertNull(user.httpCredentialsSnapshot().accessToken)
            assertEquals(0L, client.currentConnectionGeneration)

            assertTrue(prepared.start())
        } finally {
            runCatching { session?.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
        }
    }

    @Test
    fun `telemetry storage failure does not block an otherwise valid local session`() = runBlocking {
        val identity = DeploymentIdentity.from(
            tcpHost = UNREACHABLE_HOST,
            tcpPort = 5100,
            serverUrl = "https://offline.test.example",
        )
        val user = UserSession().apply {
            restorePersistedLogin(OWNER_UID, "offline-session", TEST_SYNC_DATASET_ID)
        }
        val expectedCache = FakeLocalCache()
        val client = ImClient()
        val invalidTelemetryRoot = Files.createTempFile("teamtalk-invalid-telemetry-root-", ".tmp")
        var session: ClientSession? = null
        try {
            client.prepareAuthentication(
                uid = OWNER_UID,
                token = "offline-session",
                deviceId = "offline-telemetry-test-device",
                deviceName = "Offline telemetry test",
                host = UNREACHABLE_HOST,
                port = 5100,
            )
            withTimeout(5_000L) { client.awaitTransportOwnerStart() }

            session = createSession(
                imClient = client,
                userSession = user,
                deploymentIdentity = identity,
                createCache = { _, _, _ -> expectedCache },
                deviceId = "offline-telemetry-test-device",
                logUploadEnabled = true,
                telemetrySpoolRoot = invalidTelemetryRoot.toFile(),
            )

            assertEquals(OWNER_UID, session.ownerUid)
            assertSame(expectedCache, session.localCache)
            assertNull(session.telemetryRecorder)
        } finally {
            runCatching { session?.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
            Files.deleteIfExists(invalidTelemetryRoot)
        }
    }

    private companion object {
        const val OWNER_UID = "offline-owner"
        const val UNREACHABLE_HOST = "203.0.113.1"
    }
}
