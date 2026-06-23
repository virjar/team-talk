package com.virjar.tk.viewmodel

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
    protected val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }
    protected fun setError(msg: String) { _error.value = msg }

    /** 释放资源。子类可 override 添加清理逻辑（如通知 LocalCache 释放窗口）。 */
    open fun destroy() { scope.cancel() }
}
