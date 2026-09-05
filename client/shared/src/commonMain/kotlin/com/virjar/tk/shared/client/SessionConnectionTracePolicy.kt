package com.virjar.tk.shared.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 把连接身份变化绑定到权威 HTTP 遥测策略心跳。
 *
 * AUTH 携带的 trace 上下文只是唤醒提示。策略准入仍归 uploader 的 HTTP 响应所有，并且该观察者
 * 绝不会在应用刷新后的策略之前刷写更早物理连接准入的事件。
 */
internal fun ClientTelemetryUploader.bindConnectionTracePolicyRefresh(
    imClient: ImClient,
    scope: CoroutineScope,
) {
    imClient.connectionTraceContext
        .drop(1)
        .onEach { refreshPolicyForConnectionTraceChange() }
        .launchIn(scope)

    // BASELINE 重连保持 null -> null，因此不产生 StateFlow 变化。
    // 该情况下在认证恢复后刷新一次；DIAGNOSTIC 重连已被上面新鲜的非 null 关联上下文覆盖。
    imClient.state
        .drop(1)
        .filter { state ->
            state == ConnectionState.AUTHENTICATED && imClient.connectionTraceContext.value == null
        }
        .onEach { refreshPolicyForConnectionTraceChange() }
        .launchIn(scope)
}
