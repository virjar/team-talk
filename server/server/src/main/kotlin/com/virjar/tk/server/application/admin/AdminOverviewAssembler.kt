package com.virjar.tk.server.application.admin

import com.virjar.tk.server.domain.session.OnlineSessions
import java.time.Clock
import java.time.LocalDate

internal class AdminOverviewAssembler(
    private val users: AdminUserDirectory,
    private val chats: AdminChatDirectory,
    private val onlineSessions: OnlineSessions,
    private val diagnostics: AdminDiagnostics,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun load(): AdminOverview {
        val dayStart = currentLocalDayStartMillis(clock)
        val storage = diagnostics.storageUsage()
        return AdminOverview(
            onlineCount = onlineSessions.onlineCount(),
            userCount = users.countUsers(),
            groupCount = chats.countGroups(),
            todayEvents = chats.countEventsSince(dayStart),
            storageRocksdbBytes = storage.rocksdbBytes,
            storageFileStoreBytes = storage.fileStoreBytes,
            storageScanTruncated = storage.truncated,
        )
    }
}

internal fun currentLocalDayStartMillis(clock: Clock): Long =
    LocalDate.now(clock).atStartOfDay(clock.zone).toInstant().toEpochMilli()
