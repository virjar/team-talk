package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/**
 * 认证服务 RPC IDL（仅会话管理；REGISTER/LOGIN/REFRESH 走 TCP 握手不走 RPC）。
 * 每个方法显式声明当前协议基线的 methodId。
 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("auth")
interface AuthRpc {
    @RpcMethod(1)
    suspend fun logout()

    @RpcMethod(2)
    suspend fun updatePassword(oldPassword: String, newPassword: String)
}
