package com.virjar.tk.shared.client

/**
 * 可靠命令槽共享的 Healthy/Poisoned 骨架。加载时“读取成功但不规范”的记录被隔离为
 * Poisoned——一个槽损坏不得以丢失全部离线投影为代价，未知操作保留给账号数据清理，
 * 禁止覆盖；SQL/打开失败在 load 闭包内的查询阶段照常抛出，使 LocalCache 构造失败。
 *
 * 调用方持有所属 LocalCache 的 stateLock；毒化槽的任何读写都抛族专属异常。
 */
internal class PendingCommandSlot<T>(
    load: () -> T,
    corrupt: (Throwable) -> IllegalStateException,
) {
    private sealed interface State

    private data class Healthy<T>(val value: T) : State

    private data class Poisoned(val failure: IllegalStateException) : State

    private var state: State = try {
        Healthy(load())
    } catch (failure: IllegalArgumentException) {
        Poisoned(corrupt(failure))
    } catch (failure: IllegalStateException) {
        Poisoned(corrupt(failure))
    }

    fun healthy(): T = when (val current = state) {
        is Healthy<*> -> @Suppress("UNCHECKED_CAST") (current.value as T)
        is Poisoned -> throw current.failure
    }

    fun update(value: T) {
        state = Healthy(value)
    }
}
