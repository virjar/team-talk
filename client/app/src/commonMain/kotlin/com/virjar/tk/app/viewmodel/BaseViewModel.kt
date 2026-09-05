package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.logUnhandledError
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
    private val onAuthExpired: () -> Unit = {},
) {
    protected val scope = CoroutineScope(dispatcher + SupervisorJob() +
        CoroutineExceptionHandler { _, throwable ->
            if (throwable is AppError.AuthExpired) {
                handleAuthExpired()
            } else {
                setError("Unhandled error: ${throwable.message}")
                logUnhandledError("ViewModel", throwable)
            }
        })

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 认证失效统一出口：提示 + 上抛给 [onAuthExpired]。 */
    protected fun handleAuthExpired() {
        setError("认证失效，请重新登录")
        onAuthExpired()
    }

    fun clearError() { _error.value = null }
    protected fun setError(msg: String) { _error.value = msg }

    /** 执行一个被拥有的动作，而不把生命周期取消变成可见的失败。 */
    protected suspend fun runViewModelAction(
        failurePrefix: String,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AppError.AuthExpired) {
            handleAuthExpired()
        } catch (throwable: Exception) {
            setError("$failurePrefix: ${throwable.message}")
        }
    }

    /** 释放资源。子类可 override 添加清理逻辑（如通知 LocalCache 释放窗口）。 */
    open fun destroy() { scope.cancel() }
}
