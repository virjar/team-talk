package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.protocol.*

class AuthRouteHandler(
    private val authService: AuthService,
    private val userService: UserService,
) {
    suspend fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (AuthMethod.fromId(methodId)) {
            AuthMethod.LOGOUT -> ProtoCodec.withPayload(payload!!) {
                val refreshToken = readString()
                authService.logout(uid, refreshToken)
                ByteArray(0)
            }
            // REFRESH_TOKEN/REGISTER/LOGIN 在 TCP 握手阶段处理，不应通过 RPC 调用。
            // 抛异常让 RpcDispatcher 返回 400（原 ByteArray(0) 会误报成功）
            AuthMethod.REFRESH_TOKEN,
            AuthMethod.REGISTER,
            AuthMethod.LOGIN -> throw IllegalArgumentException("Use TCP handshake for auth")
            AuthMethod.UPDATE_PASSWORD -> ProtoCodec.withPayload(payload!!) {
                userService.changePassword(uid, readString()!!, readString()!!)
                ByteArray(0)
            }
        }
    }
}
