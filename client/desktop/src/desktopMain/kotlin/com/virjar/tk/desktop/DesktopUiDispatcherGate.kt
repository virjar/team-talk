package com.virjar.tk.desktop

import com.virjar.tk.shared.log.AppLog
import java.io.Closeable
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/**
 * 可能来自 Netty/Default/AWT 线程的回调的唯一 Desktop 边界。
 *
 * [dispatchToUi] 由 application 组合提供，因此指向其 Compose dispatcher。私有队列即使在
 * 多个生产者线程竞争时也能保持提交顺序，并确保某个失败的回调不会卡住后续 UI 工作。
 */
internal class DesktopUiDispatcherGate(
    private val dispatchToUi: ((() -> Unit) -> Unit),
    private val reportFailure: (Throwable) -> Unit = { failure ->
        AppLog.fault("DesktopUiGate", "Desktop UI callback failed", failure)
    },
    private val ordinaryCapacity: Int = DEFAULT_ORDINARY_CAPACITY,
    private val onceKeyCapacity: Int = DEFAULT_ONCE_KEY_CAPACITY,
) : Closeable {
    private data class PendingAction(
        val onceKey: Any?,
        val action: () -> Unit,
    )

    private val lock = Any()
    private val pending = ArrayDeque<PendingAction>()
    private val onceKeys = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var pendingOrdinaryCount = 0
    private var pendingOnceCount = 0
    private var dispatchScheduled = false
    private var closed = false

    init {
        require(ordinaryCapacity > 0) { "Desktop UI ordinary capacity must be positive" }
        require(onceKeyCapacity > 0) { "Desktop UI once-key capacity must be positive" }
    }

    fun dispatch(action: () -> Unit): Boolean = enqueue(onceKey = null, action = action)

    /** 原子地认领 [onceKey] 并排队 [action]，因此并发的终结边沿只执行一次。 */
    fun dispatchOnce(onceKey: Any, action: () -> Unit): Boolean =
        enqueue(onceKey = onceKey, action = action)

    fun forgetOnceKeys(vararg keys: Any) = synchronized(lock) {
        keys.forEach(onceKeys::remove)
    }

    override fun close() = synchronized(lock) {
        closed = true
        pending.clear()
        pendingOrdinaryCount = 0
        pendingOnceCount = 0
        onceKeys.clear()
    }

    private fun enqueue(onceKey: Any?, action: () -> Unit): Boolean {
        val schedule = synchronized(lock) {
            if (closed) return false
            if (onceKey == null) {
                if (pendingOrdinaryCount >= ordinaryCapacity) return false
                pendingOrdinaryCount += 1
            } else {
                if (
                    onceKey in onceKeys ||
                    onceKeys.size >= onceKeyCapacity ||
                    pendingOnceCount >= onceKeyCapacity
                ) {
                    return false
                }
                onceKeys.add(onceKey)
                pendingOnceCount += 1
            }
            pending.addLast(PendingAction(onceKey, action))
            if (dispatchScheduled) {
                false
            } else {
                dispatchScheduled = true
                true
            }
        }
        if (schedule) {
            try {
                dispatchToUi(::drainOnUi)
            } catch (failure: Throwable) {
                synchronized(lock) {
                    dispatchScheduled = false
                    clearPendingLocked(releaseOnceKeys = true)
                }
                if (failure is CancellationException || failure !is Exception) throw failure
                reportOrdinaryFailure(failure)
            }
        }
        return true
    }

    private fun drainOnUi() {
        while (true) {
            val pendingAction = synchronized(lock) {
                if (closed) {
                    clearPendingLocked(releaseOnceKeys = false)
                    dispatchScheduled = false
                    return
                }
                val next = pending.pollFirst()
                if (next == null) {
                    dispatchScheduled = false
                    return
                }
                if (next.onceKey == null) pendingOrdinaryCount -= 1 else pendingOnceCount -= 1
                next
            }
            try {
                pendingAction.action()
            } catch (failure: Throwable) {
                if (failure is CancellationException || failure !is Exception) {
                    synchronized(lock) {
                        dispatchScheduled = false
                        clearPendingLocked(releaseOnceKeys = true)
                    }
                    throw failure
                }
                reportOrdinaryFailure(failure)
            }
        }
    }

    private fun clearPendingLocked(releaseOnceKeys: Boolean) {
        while (pending.isNotEmpty()) {
            val removed = pending.removeFirst()
            if (removed.onceKey == null) {
                pendingOrdinaryCount -= 1
            } else {
                pendingOnceCount -= 1
                if (releaseOnceKeys) onceKeys.remove(removed.onceKey)
            }
        }
        check(pendingOrdinaryCount == 0) { "Desktop UI pending ordinary count drifted" }
        check(pendingOnceCount == 0) { "Desktop UI pending once count drifted" }
    }

    /** 诊断可以普通地失败，但 VM 级的致命 reporter 失败仍必须向外抛出。 */
    private fun reportOrdinaryFailure(failure: Exception) {
        try {
            reportFailure(failure)
        } catch (_: Exception) {
            // 原始的 UI 回调已被隔离；诊断不能卡住队列。
        }
    }

    private companion object {
        const val DEFAULT_ORDINARY_CAPACITY = 256
        const val DEFAULT_ONCE_KEY_CAPACITY = 32
    }
}

/**
 * 应用级退出准入。认证 presentation 退役之后，托盘/窗口关闭事件仍保持权威；
 * 只有 application UI dispatcher 被销毁才能拒绝它。identity once-key 让并发的原生关闭来源保持幂等。
 */
internal class DesktopApplicationExitActions(
    private val gate: DesktopUiDispatcherGate,
    private val onExitApplication: () -> Unit,
) : Closeable {
    private val lock = Any()
    private val exitKey = Any()
    private var closed = false

    fun requestExit(): Boolean = synchronized(lock) {
        if (closed) return false
        gate.dispatchOnce(exitKey, onExitApplication)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            gate.forgetOnceKeys(exitKey)
        }
    }
}

/** 经 [DesktopUiDispatcherGate] 路由的会话级业务命令。 */
internal class DesktopSessionUiActions(
    private val gate: DesktopUiDispatcherGate,
    private val presentationGate: DesktopSessionPresentationGate,
    private val onAuthExpired: () -> Unit,
    private val onLogout: () -> Unit,
    private val onHttpAuthExpired: (rejectedAccessToken: String) -> Boolean,
) : Closeable {
    private val lifecycleLock = Any()
    private val terminalKey = Any()
    private var closed = false
    private var terminalRequested = false

    fun dispatchUi(action: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (closed || terminalRequested || !presentationGate.isOpen) return false
        gate.dispatch {
            val mayRun = synchronized(lifecycleLock) {
                !closed && !terminalRequested
            }
            if (mayRun) presentationGate.runIfOpen(action)
        }
    }

    fun requestAuthExpired(): Boolean = requestTerminal(onAuthExpired)

    /**
     * 带 bearer 限定的 401 在 UI/AuthController 边界上、presentation 关闭之前做出裁决。
     * 因此过期的拒绝会让本 owner 保持开放，以便处理稍后用当前 token 触发的 401。
     */
    fun requestHttpAuthExpired(rejectedAccessToken: String): Boolean = synchronized(lifecycleLock) {
        if (closed || terminalRequested || !presentationGate.isOpen) return false
        gate.dispatch {
            val mayRun = synchronized(lifecycleLock) {
                !closed && !terminalRequested
            }
            if (!mayRun || !presentationGate.isOpen) return@dispatch
            if (onHttpAuthExpired(rejectedAccessToken)) {
                synchronized(lifecycleLock) { terminalRequested = true }
            }
        }
    }

    fun requestLogout(): Boolean = requestTerminal(onLogout)

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            gate.forgetOnceKeys(terminalKey)
        }
    }

    private fun requestTerminal(action: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (closed || terminalRequested || !presentationGate.isOpen) return false
        terminalRequested = true
        gate.dispatchOnce(terminalKey) {
            val mayRun = synchronized(lifecycleLock) {
                !closed
            }
            // 终结工作在一个准入监视器之外执行。获胜的 close 先排空所有普通 handler；
            // 失败则意味着另一个退役是权威的，这个过期回调被丢弃。期望会话的 AuthState 闭包
            // 是针对在该队列条目被捕获之后安装的后继者的第二道身份栅栏。
            if (mayRun && presentationGate.close()) {
                action()
            }
        }
    }
}

internal fun isDesktopWindowActive(visible: Boolean, focused: Boolean): Boolean = visible && focused

internal enum class DesktopMainWindowCloseAction { HIDE_TO_TRAY, EXIT_APPLICATION }

internal fun desktopMainWindowCloseAction(trayAvailable: Boolean): DesktopMainWindowCloseAction =
    if (trayAvailable) DesktopMainWindowCloseAction.HIDE_TO_TRAY
    else DesktopMainWindowCloseAction.EXIT_APPLICATION
