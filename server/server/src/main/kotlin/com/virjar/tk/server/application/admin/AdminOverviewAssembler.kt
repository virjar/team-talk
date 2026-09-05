package com.virjar.tk.server.application.admin

import com.virjar.tk.server.domain.session.OnlineSessions
import java.time.Clock
import java.time.LocalDate

/** 概览计数器的领域适配器；组装器本身只接触这个狭窄的计数器端口。 */
class DomainAdminOverviewCounters(
    private val onlineSessions: OnlineSessions,
    private val chats: AdminChatDirectory,
) : AdminOverviewCounters {
    override suspend fun onlineCount(): Int = onlineSessions.onlineCount()
    override fun groupCount(): Long = chats.countGroups()
    override fun eventCountSince(sinceMillis: Long): Long = chats.countEventsSince(sinceMillis)
}

internal class AdminOverviewAssembler(
    private val users: AdminUserDirectory,
    private val counters: AdminOverviewCounters,
    private val diagnostics: AdminDiagnostics,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun load(): AdminOverview {
        val dayStart = currentLocalDayStartMillis(clock)
        val storage = diagnostics.storageUsage()
        return AdminOverview(
            onlineCount = counters.onlineCount(),
            userCount = users.countUsers(),
            groupCount = counters.groupCount(),
            todayEvents = counters.eventCountSince(dayStart),
            storageRocksdbBytes = storage.rocksdbBytes,
            storageFileStoreBytes = storage.fileStoreBytes,
            storageScanTruncated = storage.truncated,
        )
    }
}

internal fun currentLocalDayStartMillis(clock: Clock): Long =
    LocalDate.now(clock).atStartOfDay(clock.zone).toInstant().toEpochMilli()
