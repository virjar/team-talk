package com.virjar.tk.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 在会话拥有的协程作用域中执行异步 UI 命令。
 *
 * 平台 route/Window 作用域只是等待方。工作在准入检查通过后同步开始，先于准入租约释放，
 * 并且在调用方挂起或被销毁后仍归会话所有。会话退役会取消 [ownerScope]。最后的准入检查
 * 防止已完成的过期命令发布导航或其他 UI 结果。
 */
internal class SessionUiActionExecutor(
    private val ownerScope: CoroutineScope,
) {
    suspend fun <T> execute(
        admission: UiActionAdmission,
        onClosed: () -> T,
        action: suspend () -> T,
    ): T {
        val result = CompletableDeferred<T>()
        val admitted = admission.runIfOpen {
            ownerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    result.complete(action())
                } catch (cancelled: CancellationException) {
                    result.cancel(cancelled)
                    throw cancelled
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                    // 等待结果的 route 可能已被销毁。致命 VM/链接/断言错误仍必须送达 owner 作用域的异常处理器。
                    if (failure !is Exception) throw failure
                }
            }
        }
        if (!admitted) return onClosed()
        val completed = result.await()
        return if (admission.runIfOpen {}) completed else onClosed()
    }

    /** 原生选择器与权限结果回调使用的即发即弃（fire-and-forget）变体。 */
    fun launch(
        admission: UiActionAdmission,
        action: suspend () -> Unit,
    ): Boolean = launchCancellable(admission, action) != null

    /**
     * 针对富媒体资源上传的窄化可取消变体，其 UI 提供显式的取消操作。
     * 其他即发即弃命令继续使用 [launch]，不获取任务身份。
     */
    fun launchCancellable(
        admission: UiActionAdmission,
        action: suspend () -> Unit,
    ): Job? {
        var task: Job? = null
        val admitted = admission.runIfOpen {
            task = ownerScope.launch(start = CoroutineStart.UNDISPATCHED) { action() }
        }
        return task.takeIf { admitted }
    }
}
