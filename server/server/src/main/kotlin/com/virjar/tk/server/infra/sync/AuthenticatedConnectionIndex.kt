package com.virjar.tk.server.infra.sync

/** 一次拥有者线程准入 [AuthenticatedConnectionIndex] 的结果。 */
internal sealed interface IndexedConnectionAdmission<out T> {
    data class Admitted<T>(val replaced: List<T>) : IndexedConnectionAdmission<T>
    data object LimitReached : IndexedConnectionAdmission<Nothing>
}

/**
 * 每个身份绑定连接的每用户索引，包括同步中与活跃会话。
 *
 * 调用方拥有序列化（ClientRegistry 的 Looper）。替换同一设备是一次索引
 * 变更：该设备的所有更旧条目在候选者可见之前被移除，
 * 且这些条目不消耗额外容量。不同的设备不能越过 [capacity]。
 */
internal class AuthenticatedConnectionIndex<T>(
    private val capacity: Int,
    private val uidOf: (T) -> String,
    private val deviceIdOf: (T) -> String,
) {
    private val sessionsByUid = mutableMapOf<String, LinkedHashSet<T>>()

    init {
        require(capacity > 0) { "Authenticated connection capacity must be positive" }
    }

    fun admit(candidate: T): IndexedConnectionAdmission<T> {
        val uid = uidOf(candidate)
        val deviceId = deviceIdOf(candidate)
        require(uid.isNotBlank()) { "Authenticated connection uid must not be blank" }
        require(deviceId.isNotBlank()) { "Authenticated connection device id must not be blank" }

        val sessions = sessionsByUid.getOrPut(uid) { linkedSetOf() }
        check(candidate !in sessions) { "Authenticated connection is already indexed" }
        val replaced = sessions.filter { existing -> deviceIdOf(existing) == deviceId }
        if (sessions.size - replaced.size >= capacity) {
            if (sessions.isEmpty()) sessionsByUid.remove(uid)
            return IndexedConnectionAdmission.LimitReached
        }

        sessions.removeAll(replaced.toSet())
        check(sessions.add(candidate)) { "Authenticated connection index rejected a new identity" }
        return IndexedConnectionAdmission.Admitted(replaced)
    }

    fun contains(session: T): Boolean = sessionsByUid[uidOf(session)]?.contains(session) == true

    fun forUser(uid: String): List<T> = sessionsByUid[uid]?.toList().orEmpty()

    fun forDevice(uid: String, deviceId: String): List<T> =
        sessionsByUid[uid]?.filter { session -> deviceIdOf(session) == deviceId }.orEmpty()

    fun all(): List<T> = sessionsByUid.values.flatMap { it.toList() }

    fun remove(session: T): Boolean {
        val uid = uidOf(session)
        val sessions = sessionsByUid[uid] ?: return false
        val removed = sessions.remove(session)
        if (sessions.isEmpty()) sessionsByUid.remove(uid)
        return removed
    }

    fun sizeFor(uid: String): Int = sessionsByUid[uid]?.size ?: 0

    fun totalSize(): Int = sessionsByUid.values.sumOf { sessions -> sessions.size }

    fun clear() {
        sessionsByUid.clear()
    }
}
