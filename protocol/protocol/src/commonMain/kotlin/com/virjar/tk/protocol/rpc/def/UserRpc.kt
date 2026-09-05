package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 用户服务 RPC IDL；每个方法显式声明当前协议基线的 methodId。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("user")
interface UserRpc {
    /** targetUid 为 null/空 = 查自己。 */
    @RpcMethod(1)
    suspend fun getProfile(targetUid: String?): User
    @RpcMethod(2)
    suspend fun updateProfile(patch: ProfilePatch)
    @RpcMethod(3)
    suspend fun search(keyword: String): List<User>
}
