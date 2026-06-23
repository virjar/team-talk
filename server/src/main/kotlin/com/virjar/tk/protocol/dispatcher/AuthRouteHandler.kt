package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.protocol.*

class AuthRouteHandler(
    private val authService: AuthService,
    private val userService: UserService,
) {
    fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (AuthMethod.fromId(methodId)) {
            AuthMethod.LOGOUT -> ProtoCodec.withPayload(payload!!) {
                val refreshToken = readString()
                authService.logout(uid, refreshToken)
                ByteArray(0)
            }
            // REFRESH_TOKEN/REGISTER/LOGIN 在 TCP 握手阶段处理，不应通过 RPC 调用
            AuthMethod.REFRESH_TOKEN -> ByteArray(0)
            AuthMethod.REGISTER, AuthMethod.LOGIN -> ByteArray(0)
            AuthMethod.UPDATE_PASSWORD -> ProtoCodec.withPayload(payload!!) {
                userService.changePassword(uid, readString()!!, readString()!!)
                ByteArray(0)
            }
        }
    }
}
