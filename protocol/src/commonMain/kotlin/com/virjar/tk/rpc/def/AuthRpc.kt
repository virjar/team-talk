package com.virjar.tk.rpc.def

import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/**
 * 认证服务 RPC IDL（仅会话管理；REGISTER/LOGIN/REFRESH 走 TCP 握手不走 RPC）。
 * 每个方法显式声明稳定 methodId。
 */
@RpcService("auth")
interface AuthRpc {
    @RpcMethod(1)
    suspend fun logout(refreshToken: String?, deviceId: String?)

    @RpcMethod(2)
    suspend fun updatePassword(oldPassword: String, newPassword: String)
}
