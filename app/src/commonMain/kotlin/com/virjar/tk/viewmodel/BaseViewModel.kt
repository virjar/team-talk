package com.virjar.tk.viewmodel

import com.virjar.tk.client.logUnhandledError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException

/**
 * ViewModel 基类。提供共享的协程作用域和错误状态管理。
 *
 * @param dispatcher 作用域调度器。生产默认 [Dispatchers.Default]（F27：Main
 *   dispatcher 从后台线程 launch 曾静默丢失）；测试注入 StandardTestDispatcher
 *   才能用 advanceUntilIdle 确定性推进（否则真实线程池上竞态）。
 */
abstract class BaseViewModel(
    protected val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    protected val scope = CoroutineScope(dispatcher + SupervisorJob() +
        CoroutineExceptionHandler { _, throwable ->
            setError("Unhandled error: ${throwable.message}")
            logUnhandledError("ViewModel", throwable)
        })

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 认证失效回调。子类在捕获 [AppError.AuthExpired] 时调用，
     * 由外部（AppDataState）统一订阅并执行 session.close()——
     * ViewModel 自身不直接断连（owner-driven：连接层由会话所有者管理）。
     */
    var onAuthExpired: (() -> Unit)? = null

    /** 认证失效统一出口：提示 + 上抛给 [onAuthExpired]。 */
    protected fun handleAuthExpired() {
        setError("认证失效，请重新登录")
        onAuthExpired?.invoke()
    }

    fun clearError() { _error.value = null }
    protected fun setError(msg: String) { _error.value = msg }

    /** Execute an owned action without turning lifecycle cancellation into a visible failure. */
    protected suspend fun runViewModelAction(
        failurePrefix: String,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            setError("$failurePrefix: ${throwable.message}")
        }
    }

    /** 释放资源。子类可 override 添加清理逻辑（如通知 LocalCache 释放窗口）。 */
    open fun destroy() { scope.cancel() }
}
