package com.virjar.tk.util

import com.virjar.tk.log.TkLogger
import java.io.File

/**
 * One immutable process-log ownership snapshot. Buffers, fault callback and crash attribution move
 * together; callers that outlive a session may retain this object and can never append into a
 * replacement account's buffers.
 */
internal class AppLogOwner(
    internal val traceBuffer: LogBuffer,
    internal val faultBuffer: LogBuffer,
    private val onFault: (() -> Unit)?,
    private val crashSink: ((File, String) -> Unit)?,
) {
    private val lifecycleLock = Any()

    /** Retirement is terminal: ordinary fixed loggers can no longer append or schedule uploads. */
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
    }

    internal fun appendFault(tag: String, msg: String, throwable: Throwable? = null) = synchronized(lifecycleLock) {
        if (!restorable) return@synchronized
        faultBuffer.append("fault", tag, msg, throwable)
        runCatching { onFault?.invoke() }
    }

    /** Cleanup diagnostics stay in this fixed namespace without scheduling a retired uploader. */
    internal fun recordCleanupFault(tag: String, msg: String, throwable: Throwable? = null) {
        platformLog("fault", tag, msg, throwable)
        faultBuffer.append("fault", tag, msg, throwable)
    }

    internal fun flushCrash(dataDir: File, content: String): Boolean {
        val sink = crashSink ?: return false
        sink(dataDir, content)
        return true
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
 * Fixed process-platform diagnostics that never consult the mutable AppLog owner slot. Connection
 * trees use this before an authenticated ClientSession exists and disabled/headless clients keep
 * it for their whole lifetime, so they cannot borrow another account's buffers or fault handler.
 */
internal class PlatformOnlyTkLogger(private val name: String) : TkLogger {
    override fun trace(msg: String) = platformLog("trace", name, msg, null)
    override fun fault(msg: String, t: Throwable?) = platformLog("fault", name, msg, t)
}

/**
 * Cross-platform process logging. [owner] is the only mutable ownership slot and is published with
 * one volatile write, so a log call can never combine one account's buffers with another account's
 * handler or crash namespace.
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

    /** Transactional construction rollback: restore only if nobody replaced the failed owner. */
    internal fun restoreAfterFailedInstall(
        failedOwner: AppLogOwner,
        previousOwner: AppLogOwner?,
    ): Boolean {
        // Keep owner lifecycle -> global slot as the only nested lock order. A fault callback is
        // admitted under the owner lock and may synchronously initiate session retirement.
        failedOwner.markRetired()
        return synchronized(this) {
            if (owner !== failedOwner) return@synchronized false
            owner = previousOwner?.takeIf { it.isRestorable() }
            true
        }
    }

    /** Compare-and-release: a retiring session cannot clear a replacement owner's snapshot. */
    internal fun release(expectedOwner: AppLogOwner): Boolean {
        // Hidden owners still retire permanently. A later construction rollback must not revive an
        // uploader whose session already crossed quiesce while another owner occupied the slot.
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

/**
 * TkLogger 的 AppLog 实现。shared 模块的日志通过此桥接器输出到 AppLog。
 * 由客户端启动时注入 TkLoggerFactory。
 */
class AppLogTkLogger(private val name: String) : TkLogger {
    override fun trace(msg: String) = AppLog.trace(name, msg)
    override fun fault(msg: String, t: Throwable?) = AppLog.fault(name, msg, t)
}

internal expect fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?)
