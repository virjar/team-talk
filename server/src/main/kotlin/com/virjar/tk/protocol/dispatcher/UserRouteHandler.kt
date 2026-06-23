package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.user.UserService
import com.virjar.tk.model.User
import com.virjar.tk.protocol.*

class UserRouteHandler(private val userService: UserService) {
    suspend fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (UserMethod.fromId(methodId)) {
            UserMethod.GET_PROFILE -> {
                val targetUid = ProtoCodec.withPayload(payload) { readString() ?: uid }
                ProtoCodec.encode(userService.getProfile(targetUid))
            }
            UserMethod.UPDATE_PROFILE -> {
                val user = ProtoCodec.decode(User, payload!!)
                userService.updateProfile(uid, user.name, user.avatar, user.sex, user.phone)
                ByteArray(0)
            }
            UserMethod.SEARCH -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encodeList(userService.search(readString()!!))
            }
        }
    }
}
