package com.virjar.tk.server.domain.transaction

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType

/**
 * 暴露给领域服务、而不泄露 Exposed 类型的 PostgreSQL 命令边界。
 *
 * [block] 所做的仓储调用必须加入外层事务。持久化事件意图在命令运行期间编码，并且仅在
 * [block] 返回之后持久化。这个顺序使按用户区分的流行成为命令获取的最后数据库锁。
 */
interface PgUnitOfWork {
    /**
     * 从单个可重复读、只读快照运行一个授权敏感查询。该块被刻意设计为不挂起：
     * 它只能组合事务作用域的仓储读取，且不能一边等待其他子系统一边逃出快照。
     */
    suspend fun <T> read(block: PgReadScope.() -> T): T

    /**
     * 在单个事务中运行一个聚合命令。
     *
     * 该块被刻意设计为不挂起：一旦获准进入，它在不可取消的提交/发布边界内运行，且只能
     * 组合事务作用域的仓储操作。网络调用、延迟与其他协程等待必须发生在准入之前，否则
     * 它们可能在调用方离开之后仍然持有数据库锁。
     */
    suspend fun <T> write(block: PgWriteScope.() -> T): T
}

/** 证明一个仓储查询已加入活跃外层事务的不透明句柄。 */
interface PgReadTransactionContext

/**
 * 证明一个仓储变更已加入可写外层事务的不透明句柄。写句柄也是读句柄，因此一个命令可以
 * 组合查询与变更，而无需为了让变更端口接受只读快照而削弱它们。
 */
interface PgWriteTransactionContext : PgReadTransactionContext

interface PgReadScope {
    val transaction: PgReadTransactionContext
}

interface PgWriteScope : PgReadScope {
    override val transaction: PgWriteTransactionContext

    /**
     * 在提交时把一条持久化事件追加到接收者按用户区分作用域的流。命令或投影重试必须
     * 先使用它们自己的同事务 applied/receipt 事实；可压缩的事件日志被刻意设计成
     * 不是第二个幂等存储。
     */
    fun appendEvent(
        uid: String,
        notifyType: NotifyType,
        payload: IProto,
    )

    /**
     * 注册一个进程本地缓存/失效回调。
     *
     * 它仅在数据库提交成功之后、实时分发器被通知之前运行，因此事件触发的读取不会观察到
     * 更旧的进程本地缓存快照。持久化分发器有自己的启动扫描，因此崩溃恢复绝不能依赖
     * 该回调。
     */
    fun afterCommit(action: () -> Unit)
}
