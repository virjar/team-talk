package com.virjar.tk.server.api

import com.virjar.tk.protocol.telemetry.ClientTelemetryValidation
import com.virjar.tk.protocol.telemetry.TelemetryPolicy

/**
 * 有界键 + 分钟/日固定窗口 + 机会式清理的共享骨架；两道闸门只声明各自的维度与准入规则。
 */
internal abstract class BoundedMinuteDayWindows(
    protected val maxTrackedDevices: Int,
) {
    protected data class Window(
        var minute: Long = Long.MIN_VALUE,
        var minuteCount: Long = 0L,
        var day: Long = Long.MIN_VALUE,
        var dayBytes: Long = 0L,
        var lastSeenAt: Long = 0L,
    )

    protected val lock = Any()
    protected val devices = LinkedHashMap<String, Window>()
    protected val global = Window()

    init {
        require(maxTrackedDevices > 0)
    }

    /** 满额时拒绝新键；既有键永远可以继续推进自己的窗口。 */
    protected fun deviceWindow(key: String, now: Long): Window? {
        devices[key]?.let { return it }
        removeStaleDevices(now)
        if (devices.size >= maxTrackedDevices) return null
        return Window(lastSeenAt = now).also { devices[key] = it }
    }

    protected fun windowFor(key: String, now: Long): Window? = devices[key]

    protected fun roll(window: Window, now: Long) {
        val minute = Math.floorDiv(now, MINUTE_MILLIS)
        if (window.minute != minute) {
            window.minute = minute
            window.minuteCount = 0L
        }
        val day = Math.floorDiv(now, DAY_MILLIS)
        if (window.day != day) {
            window.day = day
            window.dayBytes = 0L
        }
    }

    private fun removeStaleDevices(now: Long) {
        val iterator = devices.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.lastSeenAt >= DAY_MILLIS) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_TRACKED_DEVICES = 50_000
        private const val MINUTE_MILLIS = 60_000L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

/** 每个已鉴权请求（包括一次精确的重试）都会穿过这道有界的入口闸门。 */
internal class ClientTelemetryIngressAdmission(
    maxTrackedDevices: Int = DEFAULT_MAX_TRACKED_DEVICES,
    private val requestsPerDeviceMinute: Int = DEFAULT_REQUESTS_PER_DEVICE_MINUTE,
    private val bytesPerDeviceDay: Long = DEFAULT_BYTES_PER_DEVICE_DAY,
    private val globalRequestsPerMinute: Int = DEFAULT_GLOBAL_REQUESTS_PER_MINUTE,
    private val globalBytesPerDay: Long = DEFAULT_GLOBAL_BYTES_PER_DAY,
) : BoundedMinuteDayWindows(maxTrackedDevices) {
    init {
        require(maxTrackedDevices > 0 && requestsPerDeviceMinute > 0 && bytesPerDeviceDay > 0L)
        require(globalRequestsPerMinute > 0 && globalBytesPerDay > 0L)
    }

    fun tryAdmitRequest(uid: String, deviceId: String, now: Long): Boolean = synchronized(lock) {
        val window = deviceWindow("$uid\u0000$deviceId", now) ?: return@synchronized false
        roll(window, now)
        roll(global, now)
        if (window.minuteCount >= requestsPerDeviceMinute ||
            global.minuteCount >= globalRequestsPerMinute
        ) {
            return@synchronized false
        }
        window.minuteCount++
        window.lastSeenAt = now
        global.minuteCount++
        true
    }

    fun tryAdmitBytes(uid: String, deviceId: String, bytes: Int, now: Long): Boolean = synchronized(lock) {
        require(bytes > 0)
        val window = windowFor("$uid\u0000$deviceId", now) ?: return@synchronized false
        roll(window, now)
        roll(global, now)
        val count = bytes.toLong()
        if (window.dayBytes > bytesPerDeviceDay - count || global.dayBytes > globalBytesPerDay - count) {
            return@synchronized false
        }
        window.dayBytes += count
        window.lastSeenAt = now
        global.dayBytes += count
        true
    }

    companion object {
        const val DEFAULT_REQUESTS_PER_DEVICE_MINUTE = 120
        const val DEFAULT_BYTES_PER_DEVICE_DAY = 128L * 1024L * 1024L
        const val DEFAULT_GLOBAL_REQUESTS_PER_MINUTE = 200_000
        const val DEFAULT_GLOBAL_BYTES_PER_DAY = 4L * 1024L * 1024L * 1024L
        private const val MINUTE_MILLIS = 60_000L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

/** 按策略维度统计新事件；精确的已提交重试只经过上面那道入口闸门。 */
internal class ClientTelemetryAdmission(
    maxTrackedDevices: Int = DEFAULT_MAX_TRACKED_DEVICES,
    private val globalEventsPerMinute: Int = DEFAULT_GLOBAL_EVENTS_PER_MINUTE,
    private val globalBytesPerDay: Long = DEFAULT_GLOBAL_BYTES_PER_DAY,
) : BoundedMinuteDayWindows(maxTrackedDevices) {
    init {
        require(globalEventsPerMinute > 0)
        require(globalBytesPerDay > 0L)
    }

    fun tryAdmit(
        uid: String,
        deviceId: String,
        eventCount: Int,
        uncompressedBytes: Int,
        policy: TelemetryPolicy,
        now: Long,
    ): Boolean = synchronized(lock) {
        require(eventCount >= 0 && uncompressedBytes > 0)
        ClientTelemetryValidation.requireValid(policy)
        val window = deviceWindow("$uid\u0000$deviceId", now) ?: return@synchronized false
        roll(window, now)
        roll(global, now)
        val events = eventCount.toLong()
        val bytes = uncompressedBytes.toLong()
        if (window.minuteCount + events > policy.maxEventsPerMinute ||
            window.dayBytes + bytes > policy.maxBytesPerDay ||
            global.minuteCount + events > globalEventsPerMinute ||
            global.dayBytes + bytes > globalBytesPerDay
        ) {
            return@synchronized false
        }
        window.minuteCount += events
        window.dayBytes += bytes
        window.lastSeenAt = now
        global.minuteCount += events
        global.dayBytes += bytes
        true
    }

    companion object {
        const val DEFAULT_GLOBAL_EVENTS_PER_MINUTE = 200_000
        const val DEFAULT_GLOBAL_BYTES_PER_DAY = 2L * 1024L * 1024L * 1024L
        private const val MINUTE_MILLIS = 60_000L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
