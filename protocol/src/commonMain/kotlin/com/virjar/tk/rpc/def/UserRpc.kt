package com.virjar.tk.rpc.def

import com.virjar.tk.model.ProfilePatch
import com.virjar.tk.model.User
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/** 用户服务 RPC IDL；每个方法显式声明稳定 methodId。 */
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
