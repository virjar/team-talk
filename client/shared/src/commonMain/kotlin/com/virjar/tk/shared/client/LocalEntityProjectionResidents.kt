package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Member
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

/**
 * 类 StateFlow 发布，带有独立的终态信号。
 *
 * 发布 `null` 或空列表不能退役一个当前值已经等于该哨兵的 StateFlow。独立的信号保证缓存关闭能
 * 完成每个收集器。
 */
internal class RetirableProjectionState<T>(initialValue: T) {
    /*
     * 把值与终态位放在一个 StateFlow 中。组合两个 StateFlow 会引入异步订阅窗口：一个 UNDISPATCHED
     * 收集器可以先获得其常驻项，然后当一次变更在 combine 收集到两个输入之前获胜时错过旧值。
     * 单一状态保留 StateFlow 的同步初始重放，并使退役相对发布是原子的。
     */
    private val publications = MutableStateFlow(ProjectionPublication(initialValue, isRetired = false))

    var value: T
        get() = publications.value.value
        set(value) {
            val current = publications.value
            check(!current.isRetired) { "projection state is retired" }
            publications.value = ProjectionPublication(value, isRetired = false)
        }

    fun observe(): Flow<T> = publications.takeWhile { !it.isRetired }
        .map { it.value }

    fun retire() {
        val current = publications.value
        if (!current.isRetired) publications.value = current.copy(isRetired = true)
    }

    /** 原子地抑制一次终态替换，使保留的已关闭 owner 丢弃重状态。 */
    fun retire(replacement: T) {
        val current = publications.value
        if (!current.isRetired) publications.value = ProjectionPublication(replacement, isRetired = true)
    }
}

private data class ProjectionPublication<T>(
    val value: T,
    val isRetired: Boolean,
)

/** 一个按 key 的投影，只在至少一个收集者拥有它时保留。 */
internal class KeyedEntityResident<T>(
    initialValue: T,
) {
    val flow = RetirableProjectionState(initialValue)
    var observers: Int = 0
}

/** 按 key 可变的工作集；只有 [flow] 在存储锁之外发布不可变列表。 */
internal class MemberProjectionResident(
    members: List<Member>,
) {
    val membersByUid = LinkedHashMap<String, Member>().apply {
        members.forEach { member -> put(member.uid, member) }
    }
    val flow = RetirableProjectionState(membersByUid.values.toList())
    var observers: Int = 0
}

/** 内部 JVM 测试接缝；它不是 LocalCache SDK 契约的一部分。 */
internal data class EntityProjectionResidentCounts(
    val contacts: Int,
    val users: Int,
    val userObservers: Int,
    val chats: Int,
    val chatObservers: Int,
    val memberChats: Int,
    val memberObservers: Int,
)
