package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.model.User
import com.virjar.tk.outcome
import com.virjar.tk.rpc.gen.AuthRpcProxy
import com.virjar.tk.rpc.gen.UserRpcProxy

class UserRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = UserRpcProxy(rpcClient)
    private val authRpc = AuthRpcProxy(rpcClient)

    suspend fun getProfile(uid: String): Outcome<User?> = outcome {
        rpc.getProfile(uid).also { localCache.upsertUser(it) }
    }

    suspend fun updateProfile(name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null): Outcome<Unit> = outcome {
        rpc.updateProfile(User(uid = "", username = "", name = name ?: "", avatar = avatar, sex = sex ?: 0, phone = phone))
    }

    suspend fun search(keyword: String): Outcome<List<User>> = outcome {
        rpc.search(keyword)
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Outcome<Unit> = outcome {
        authRpc.updatePassword(oldPassword, newPassword)
    }

    suspend fun logout(refreshToken: String?, deviceId: String): Outcome<Unit> = outcome {
        authRpc.logout(refreshToken, deviceId)
    }
}
