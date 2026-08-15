package com.virjar.tk.rpc.def

import com.virjar.tk.model.User
import com.virjar.tk.rpc.RpcService

/** 用户服务 RPC IDL。⚠️ methodId 稳定：新方法只追加末尾。 */
@RpcService("user")
interface UserRpc {
    /** targetUid 为 null/空 = 查自己。 */
    suspend fun getProfile(targetUid: String?): User
    suspend fun updateProfile(user: User)
    suspend fun search(keyword: String): List<User>
}
