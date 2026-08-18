package com.virjar.tk.rpc.def

import com.virjar.tk.rpc.RpcService

/**
 * 认证服务 RPC IDL（仅会话管理；REGISTER/LOGIN/REFRESH 走 TCP 握手不走 RPC）。
 * ⚠️ methodId 稳定：新方法只追加末尾。
 */
@RpcService("auth")
interface AuthRpc {
    suspend fun logout(refreshToken: String?)
    suspend fun updatePassword(oldPassword: String, newPassword: String)
}
