package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.RpcInvoker
import com.virjar.tk.client.ensureSuccess
import com.virjar.tk.model.User
import com.virjar.tk.outcome
import com.virjar.tk.protocol.AuthMethod
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ServiceId
import com.virjar.tk.protocol.UserMethod

class UserRepository(
    private val rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    /** 拉取用户资料。失败时调用方可 `.recover { localCache.getUser(uid) }` 降级。 */
    suspend fun getProfile(uid: String): Outcome<User?> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(if (uid.isNotEmpty()) uid else null) }
        val response = rpcClient.invoke(ServiceId.USER, UserMethod.GET_PROFILE.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome null
        val user = ProtoCodec.decode(User, data)
        localCache.upsertUser(user)
        user
    }

    suspend fun updateProfile(name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null): Outcome<Unit> = outcome {
        val user = User(uid = "", username = "", name = name ?: "", avatar = avatar, sex = sex ?: 0, phone = phone)
        val payload = ProtoCodec.encode(user)
        rpcClient.invoke(ServiceId.USER, UserMethod.UPDATE_PROFILE.id, payload).ensureSuccess()
    }

    suspend fun search(keyword: String): Outcome<List<User>> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(keyword) }
        val response = rpcClient.invoke(ServiceId.USER, UserMethod.SEARCH.id, payload)
        response.ensureSuccess()
        val data = response.payload ?: return@outcome emptyList()
        ProtoCodec.decodeList(User, data)
    }

    /** 返回 true=成功，false=旧密码错误；其他错误走 Failure。 */
    suspend fun changePassword(oldPassword: String, newPassword: String): Outcome<Boolean> = outcome {
        val payload = ProtoCodec.encodePayload { writeString(oldPassword); writeString(newPassword) }
        val response = rpcClient.invoke(ServiceId.AUTH, AuthMethod.UPDATE_PASSWORD.id, payload)
        response.status == 0
    }
}
