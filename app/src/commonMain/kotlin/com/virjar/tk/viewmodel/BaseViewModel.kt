package com.virjar.tk.viewmodel

import com.virjar.tk.client.logUnhandledError
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel

/**
 * ViewModel 基类。提供共享的协程作用域和错误状态管理。
 */
abstract class BaseViewModel {
    protected val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() +
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

    /** 释放资源。子类可 override 添加清理逻辑（如通知 LocalCache 释放窗口）。 */
    open fun destroy() { scope.cancel() }
}
