package com.virjar.tk.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 将 worker 持有的完成结果交还给拥有 UI 状态的协程作用域。
 *
 * ViewModel 有意在 Main 之外执行 RPC、序列化和本地数据工作。因此它们的完成回调可能在
 * worker 调度器上运行，绝不能直接修改 Compose 状态。[CoroutineStart.DEFAULT] 是刻意的：
 * UNDISPATCHED 会在进入 [ownerScope] 的调度器之前于回调线程上执行第一帧。准入检查在最终
 * 发布点运行，因此排队的结果无法更新已退役的展示。
 */
internal class UiResultHandoff(
    private val ownerScope: CoroutineScope,
) {
    fun <T> deliver(
        result: T,
        admission: UiActionAdmission,
        publish: (T) -> Unit,
    ): Job = ownerScope.launch(start = CoroutineStart.DEFAULT) {
        admission.runIfOpen { publish(result) }
    }
}
