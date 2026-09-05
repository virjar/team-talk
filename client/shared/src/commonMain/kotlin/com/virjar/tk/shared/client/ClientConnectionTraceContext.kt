package com.virjar.tk.shared.client

import com.virjar.tk.protocol.payload.ConnectionTraceContextPayload
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.protocol.telemetry.ConnectionTraceContextPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** EventLoop 持有的代际/修订隔断，带有线程安全的只读客户端快照。 */
internal class ClientConnectionTraceContext {
    private var expectedCorrelationId: String? = null
    private var expectedConnectionGeneration: Long = 0L
    private var acceptedPolicyRevision: Long = 0L
    private val mutableContext = MutableStateFlow<ConnectionTraceContext?>(null)

    val state: StateFlow<ConnectionTraceContext?> = mutableContext.asStateFlow()

    /** 在物理连接写出 AUTH 之前立即安装精确身份。 */
    fun onAuthenticationSending(connectionGeneration: Long, correlationId: String) {
        ConnectionTraceContextPolicy.requirePositive(
            connectionGeneration,
            "auth.connectionGeneration",
        )
        ConnectionTraceContextPolicy.requireToken(correlationId, "auth.correlationId")
        expectedConnectionGeneration = connectionGeneration
        expectedCorrelationId = correlationId
        acceptedPolicyRevision = 0L
        mutableContext.value = null
    }

    /** 接受当前 AUTH 的成功响应所携带的可选上下文。 */
    fun acceptAuthenticationContext(
        deliveryConnectionGeneration: Long,
        context: ConnectionTraceContext,
    ): Boolean {
        if (!matchesExpected(deliveryConnectionGeneration, context.correlationId, context.connectionGeneration)) {
            return false
        }
        if (context.policyRevision <= acceptedPolicyRevision) return false
        acceptedPolicyRevision = context.policyRevision
        mutableContext.value = context
        return true
    }

    /** 只为精确的活跃 AUTH 身份应用严格更新的实时策略修订。 */
    fun acceptUpdate(
        deliveryConnectionGeneration: Long,
        update: ConnectionTraceContextPayload,
    ): Boolean {
        if (!matchesExpected(deliveryConnectionGeneration, update.correlationId, update.connectionGeneration)) {
            return false
        }
        if (update.policyRevision <= acceptedPolicyRevision) return false
        acceptedPolicyRevision = update.policyRevision
        mutableContext.value = update.context
        return true
    }

    /** transport 丢失会同时退役公开值及其私有的 AUTH 匹配能力。 */
    fun clear() {
        expectedCorrelationId = null
        expectedConnectionGeneration = 0L
        acceptedPolicyRevision = 0L
        mutableContext.value = null
    }

    fun snapshot(nowEpochMs: Long): ConnectionTraceContext? =
        mutableContext.value?.takeUnless { it.isExpired(nowEpochMs) }

    private fun matchesExpected(
        deliveryConnectionGeneration: Long,
        correlationId: String,
        payloadConnectionGeneration: Long,
    ): Boolean =
        expectedConnectionGeneration > 0L &&
            deliveryConnectionGeneration == expectedConnectionGeneration &&
            payloadConnectionGeneration == expectedConnectionGeneration &&
            correlationId == expectedCorrelationId
}
