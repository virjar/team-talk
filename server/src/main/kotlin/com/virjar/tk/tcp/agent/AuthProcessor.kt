package com.virjar.tk.tcp.agent

import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.service.PresenceService
import com.virjar.tk.service.TokenService
import com.virjar.tk.tcp.ImAgent

/**
 * 认证处理器：处理 AUTH 请求的完整流程。
 *
 * 全部操作为纯 CPU + 内存（JWT 验签、ClientRegistry 注册、Presence 异步广播），
 * 无 DB/磁盘 IO，无 suspend，直接在 Netty EventLoop 线程同步执行。
 * 异常由调用方（ImAgent）捕获并关闭连接。
 */
object AuthProcessor {

    fun processAuth(agent: ImAgent, authRequest: AuthRequestPayload): Boolean {
        // 1. TokenService 验证 token（纯 CPU：JWT HMAC 验签）
        val validatedUid = TokenService.validateAccessToken(authRequest.token)
        if (validatedUid == null) {
            agent.sendAuthResp(AuthResponsePayload.CODE_AUTH_FAILED, "invalid token")
            agent.recorder.record { "Auth failed: invalid token" }
            return false
        }

        // 2. uid 匹配校验
        if (validatedUid != authRequest.uid) {
            agent.sendAuthResp(AuthResponsePayload.CODE_AUTH_FAILED, "uid mismatch")
            agent.recorder.record { "Auth uid mismatch: token uid=$validatedUid request uid=${authRequest.uid}" }
            return false
        }

        // 3. 注册 agent（标记已认证 + 注册到 ClientRegistry 内存表）
        agent.markAuthed(authRequest)

        // 4. 升级 Recorder（内存操作：切换到采样 Writer）
        agent.recorder.upgrade(authRequest.uid, authRequest.deviceId)

        // 5. 异步广播上线（fire-and-forget，失败不影响认证）
        PresenceService.postBroadcastOnline(authRequest.uid)

        // 6. 发送 AuthResponse（成功）
        agent.sendAuthResp(AuthResponsePayload.CODE_OK, "ok")

        agent.recorder.record { "auth done: uid=${authRequest.uid} device=${authRequest.deviceId} flag=${authRequest.flags}" }
        return true
    }
}
