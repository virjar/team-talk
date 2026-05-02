package com.virjar.tk.viewmodel

import androidx.compose.runtime.RememberObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * ViewModel 基类，实现 [RememberObserver] 自动管理协程作用域生命周期。
 * 当 Compose 从组合树中移除该实例时自动取消 scope，防止资源泄漏。
 */
abstract class BaseViewModel : RememberObserver {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var cleaned = false

    override fun onRemembered() {}

    override fun onForgotten() = cleanup()

    override fun onAbandoned() = cleanup()

    protected open fun cleanup() {
        if (cleaned) return
        cleaned = true
        scope.cancel()
    }
}
