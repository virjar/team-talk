package com.virjar.tk.app.navigation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * UI 拥有的协程与阻塞式 LocalCache/Repository 工作之间唯一的应用层边界。
 *
 * Feature 把 Compose 状态发布保持在其 UI scope 上。SQL 支持的 flow 获取以及每一个可能同步
 * 触碰 LocalCache 的 repository 用例都运行在这个可注入的 dispatcher 上；
 * Composable 只消费产生的投影值。
 */
class UiLocalDataBoundary(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <T> run(block: suspend () -> T): T = withContext(dispatcher) { block() }

    /**
     * 在存储 dispatcher 上完成取消清理。只用于释放已经准入的本地资源/事实；
     * 新的业务工作必须通过 [run] 保持可取消。
     */
    suspend fun <T> runCleanup(block: suspend () -> T): T =
        withContext(dispatcher + NonCancellable) { block() }

    /**
     * 获取一个阻塞式本地资源并原子地把它交给其内存 owner。
     *
     * 整个 acquire/install-or-release 序列不可取消，这样取消就绝不可能在 SQLite 调用
     * 和生命周期发布之间丢失一个刚创建的 pager。[install] 只能触碰 owner 的内存锁，
     * 并在该 owner 退役之后返回 false。
     */
    suspend fun <T> acquireOwned(
        acquire: () -> T,
        install: (T) -> Boolean,
        release: (T) -> Unit,
    ) = withContext(dispatcher + NonCancellable) {
        val resource = acquire()
        var installed = false
        try {
            installed = install(resource)
        } finally {
            if (!installed) release(resource)
        }
    }

    /** 源工厂本身被延迟到收集进入 [dispatcher] 之后才执行。 */
    fun <T> projection(source: () -> Flow<T>): Flow<T> = flow {
        emitAll(source())
    }.flowOn(dispatcher)
}
