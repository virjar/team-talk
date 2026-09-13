package com.virjar.tk.server.application.admin

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.User
import com.virjar.tk.server.domain.session.OnlineSessions
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminServicePortOrchestrationTest {
    @Test
    fun `overview composes narrow ports and preserves truncated storage lower bounds`() = runTest {
        var requestedEventStart: Long? = null
        val users = object : AdminUserDirectory {
            override fun listUsers(query: String?, pagination: AdminPageRequest): AdminPage<User> =
                error("not used by overview")

            override fun countUsers(): Long = 41L
        }
        val chats = object : AdminChatDirectory {
            override fun listGroups(query: String?, pagination: AdminPageRequest): AdminPage<Chat> =
                error("not used by overview")

            override fun findGroup(chatId: String): Chat? = error("not used by overview")
            override fun countGroups(): Long = 5L
            override fun countEventsSince(sinceMillis: Long): Long {
                requestedEventStart = sinceMillis
                return 11L
            }
        }
        val onlineSessions = object : OnlineSessions {
            override suspend fun onlineCount(): Int = 7
            override suspend fun isOnline(uid: String): Boolean = error("not used by overview")
            override suspend fun kickUser(uid: String): Unit = error("not used by overview")
            override suspend fun invalidateUserCredentials(uid: String, minimumEpoch: Long): Unit =
                error("not used by overview")

            override suspend fun invalidateUserCredentialsExceptSession(
                uid: String, minimumEpoch: Long, sessionId: String,
            ): Unit = error("not used by overview")

            override suspend fun invalidateDeviceCredentials(
                uid: String, deviceId: String, minimumEpoch: Long,
            ): Unit = error("not used by overview")

            override suspend fun invalidateDeviceCredentialsExceptSession(
                uid: String, deviceId: String, minimumEpoch: Long, sessionId: String,
            ): Unit = error("not used by overview")
        }
        val diagnostics = object : AdminDiagnostics {
            override fun storageUsage() = AdminStorageUsage(rocksdbBytes = 101L, fileStoreBytes = 202L, truncated = true)
            override fun listServerLogs(): List<AdminLogFileInfo> = error("not used by overview")
            override fun readServerLog(name: String, lines: Int): List<String> = error("not used by overview")
        }
        val clock = Clock.fixed(
            Instant.parse("2026-08-25T01:23:45Z"),
            ZoneId.of("Asia/Shanghai"),
        )

        val overview = AdminOverviewAssembler(users, chats, onlineSessions, diagnostics, clock = clock).load()

        assertEquals(7, overview.onlineCount)
        assertEquals(41L, overview.userCount)
        assertEquals(5L, overview.groupCount)
        assertEquals(11L, overview.todayEvents)
        assertEquals(101L, overview.storageRocksdbBytes)
        assertEquals(202L, overview.storageFileStoreBytes)
        assertTrue(overview.storageScanTruncated)
        assertEquals(Instant.parse("2026-08-24T16:00:00Z").toEpochMilli(), requestedEventStart)
    }

    @Test
    fun `event day rolls at injected local midnight rather than utc midnight`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val expectedBeforeLocalMidnight = Instant.parse("2026-08-23T16:00:00Z").toEpochMilli()
        val expectedAfterLocalMidnight = Instant.parse("2026-08-24T16:00:00Z").toEpochMilli()

        assertEquals(
            expectedBeforeLocalMidnight,
            currentLocalDayStartMillis(Clock.fixed(Instant.parse("2026-08-24T15:59:59Z"), zone)),
        )
        assertEquals(
            expectedAfterLocalMidnight,
            currentLocalDayStartMillis(Clock.fixed(Instant.parse("2026-08-24T16:00:01Z"), zone)),
        )
        assertEquals(
            expectedAfterLocalMidnight,
            currentLocalDayStartMillis(Clock.fixed(Instant.parse("2026-08-25T00:00:01Z"), zone)),
        )
    }
}
