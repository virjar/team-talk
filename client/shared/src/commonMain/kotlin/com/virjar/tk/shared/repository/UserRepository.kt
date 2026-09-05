package com.virjar.tk.shared.repository

import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.model.User
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.gen.AuthRpcProxy
import com.virjar.tk.protocol.rpc.gen.UserRpcProxy

class UserRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = UserRpcProxy(rpcClient)
    private val authRpc = AuthRpcProxy(rpcClient)

    suspend fun getProfile(uid: String): Outcome<User?> = outcome {
        val lease = localCache.beginUserSnapshot(uid)
        try {
            val remote = rpc.getProfile(uid)
            localCache.applyUserSnapshot(lease, remote)
            // USER_UPDATED 通知、一次更新的请求或投影重置都可能把这笔响应
            // 挡在栅栏外。返回收敛后的缓存值，而不是原始 RPC。
            localCache.getUser(uid)
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    suspend fun updateProfile(patch: ProfilePatch): Outcome<Unit> = outcome {
        rpc.updateProfile(patch)
    }

    suspend fun search(keyword: String): Outcome<List<User>> = outcome {
        rpc.search(keyword).map { remote ->
            // 搜索响应与 USER_UPDATED 竞争的方式与 profile 响应相同。让缓存的
            // revision 感知合并（包括其首次加载的瞬时桥接）选择可以渲染的
            // 行，而不是暴露原始 RPC 快照。
            localCache.upsertUser(remote)
            localCache.getUser(remote.uid) ?: remote
        }
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Outcome<Unit> = outcome {
        authRpc.updatePassword(oldPassword, newPassword)
    }
}
