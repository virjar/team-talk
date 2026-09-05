package com.virjar.tk.shared.log

import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 一份不可变的进程日志所有权快照。缓冲区、fault 回调与崩溃归属一起
 * 迁移；活得比会话更久的调用方可以保留此对象，且永远无法向
 * 替代账号的缓冲区追加内容。
 */
internal class AppLogOwner(
    internal val traceBuffer: LogBuffer,
    internal val faultBuffer: LogBuffer,
    private val onFault: (() -> Unit)?,
    private val crashSink: ((File, String) -> Unit)?,
    private val telemetrySink: ((String, String, String, Throwable?) -> Unit)? = null,
) {
    private val lifecycleLock = Any()

    /** 退场是终局性的：普通固定 logger 不能再追加内容或安排上传。 */
    @Volatile
    private var restorable = true

    internal fun markRetired() = synchronized(lifecycleLock) { restorable = false }

    internal fun isRestorable(): Boolean = restorable

    internal fun logger(name: String): TkLogger = FixedOwnerTkLogger(name, this)

    fun trace(tag: String, msg: String) {
        platformLog("trace", tag, msg, null)
        appendTrace(tag, msg)
    }

    fun fault(tag: String, msg: String, throwable: Throwable? = null) {
        platformLog("fault", tag, msg, throwable)
        appendFault(tag, msg, throwable)
    }

    internal fun appendTrace(tag: String, msg: String) = synchronized(lifecycleLock) {
        if (!restorable) return@synchronized
        traceBuffer.append("trace", tag, msg)
        invokeTelemetrySink("trace", tag, msg, null)
    }

    internal fun appendFault(tag: String, msg: String, throwable: Throwable? = null) = synchronized(lifecycleLock) {
        if (!restorable) return@synchronized
        faultBuffer.append("fault", tag, msg, throwable)
        invokeTelemetrySink("fault", tag, msg, throwable)
        try {
            onFault?.invoke()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 上传调度仅作诊断。VM 致命故障仍然会逃出这个边界。
        }
    }

    /** 清理诊断留在这一固定命名空间内，且不会安排已退场的上传器。 */
    internal fun recordCleanupFault(tag: String, msg: String, throwable: Throwable? = null) {
        platformLog("fault", tag, msg, throwable)
        faultBuffer.append("fault", tag, msg, throwable)
    }

    internal fun flushCrash(dataDir: File, content: String): Boolean {
        val sink = crashSink ?: return false
        sink(dataDir, content)
        return true
    }

    private fun invokeTelemetrySink(level: String, tag: String, msg: String, throwable: Throwable?) {
        try {
            telemetrySink?.invoke(level, tag, msg, throwable)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 远程诊断属于尽力而为，绝不能改变已记录的业务操作。
        }
    }
}

private class FixedOwnerTkLogger(
    private val name: String,
    private val owner: AppLogOwner,
) : TkLogger {
    override fun trace(msg: String) = owner.trace(name, msg)
    override fun fault(msg: String, t: Throwable?) = owner.fault(name, msg, t)
}

/**
 * 固定进程平台的诊断，从不查询可变的 AppLog owner 槽位。连接
 * 树在已认证的 ClientSession 存在之前使用它，禁用/无头的客户端
 * 在其整个生命周期内保留它，因此它们无法借用其他账号的缓冲区或 fault 处理器。
 */
internal class PlatformOnlyTkLogger(private val name: String) : TkLogger {
    override fun trace(msg: String) = platformLog("trace", name, msg, null)
    override fun fault(msg: String, t: Throwable?) = platformLog("fault", name, msg, t)
}

/**
 * 跨平台进程日志。[owner] 是唯一可变的所有权槽位，并借助一次
 * volatile 写发布，因此一次日志调用永远不可能把一个账号的缓冲区与另一个账号的
 * 处理器或崩溃命名空间组合在一起。
 */
object AppLog {
    @Volatile
    private var owner: AppLogOwner? = null

    internal fun install(newOwner: AppLogOwner) = synchronized(this) {
        check(newOwner.isRestorable()) { "Retired AppLog owner cannot be installed" }
        owner = newOwner
    }

    internal fun installReturningPrevious(newOwner: AppLogOwner): AppLogOwner? = synchronized(this) {
        check(newOwner.isRestorable()) { "Retired AppLog owner cannot be installed" }
        val previous = owner
        owner = newOwner
        previous
    }

    /** 事务性构造回滚：只有在没有人替换失败 owner 时才恢复。 */
    internal fun restoreAfterFailedInstall(
        failedOwner: AppLogOwner,
        previousOwner: AppLogOwner?,
    ): Boolean {
        // 让 owner 生命周期 → 全局槽位成为唯一嵌套的加锁顺序。fault 回调
        // 在 owner 锁下被准入，并可能同步发起会话退场。
        failedOwner.markRetired()
        return synchronized(this) {
            if (owner !== failedOwner) return@synchronized false
            owner = previousOwner?.takeIf { it.isRestorable() }
            true
        }
    }

    /** 比较并释放：正在退场的会话不能清除替代 owner 的快照。 */
    internal fun release(expectedOwner: AppLogOwner): Boolean {
        // 隐藏的 owner 仍然永久退场。后续的构造回滚绝不能复活一个
        // 其会话已经在另一个 owner 占据槽位期间越过 quiesce 的上传器。
        expectedOwner.markRetired()
        return synchronized(this) {
            if (owner !== expectedOwner) return@synchronized false
            owner = null
            true
        }
    }

    internal fun ownerSnapshot(): AppLogOwner? = owner

    /** 业务流程日志。开发构建自动上传，发布构建仅保存在本地。 */
    fun trace(tag: String, msg: String) {
        platformLog("trace", tag, msg, null)
        owner?.appendTrace(tag, msg)
    }

    /** 故障/异常日志。始终触发上传。 */
    fun fault(tag: String, msg: String, throwable: Throwable? = null) {
        platformLog("fault", tag, msg, throwable)
        owner?.appendFault(tag, msg, throwable)
    }

    /** 状态快照。仅本地记录，由反馈功能单独打包。 */
    fun snapshot(tag: String, msg: String) {
        platformLog("snapshot", tag, msg, null)
    }
}

internal expect fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?)
