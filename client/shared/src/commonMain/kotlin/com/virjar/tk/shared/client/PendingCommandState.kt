package com.virjar.tk.shared.client

/**
 * 可靠命令槽保存已加载的值或该命令族专属的失败。读取成功但不规范的记录只使该槽不可用，
 * 一个槽损坏不得以丢失全部离线投影为代价，未知操作保留给账号数据清理，
 * 禁止覆盖；SQL/打开失败在 load 闭包内的查询阶段照常抛出，使 LocalCache 构造失败。
 *
 * 调用方持有所属 LocalCache 的 stateLock；毒化槽的任何读写都抛族专属异常。
 */
internal class PendingCommandSlot<T>(
    load: () -> T,
    corrupt: (Throwable) -> IllegalStateException,
) {
    private var state: Result<T> = try {
        Result.success(load())
    } catch (failure: IllegalArgumentException) {
        Result.failure(corrupt(failure))
    } catch (failure: IllegalStateException) {
        Result.failure(corrupt(failure))
    }

    fun healthy(): T = state.getOrThrow()

    fun update(value: T) {
        state = Result.success(value)
    }
}
