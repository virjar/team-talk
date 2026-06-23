package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.domain.contact.ContactService
import com.virjar.tk.protocol.*

class ContactRouteHandler(private val contactService: ContactService) {
    suspend fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        return when (ContactMethod.fromId(methodId)) {
            ContactMethod.LIST -> ProtoCodec.encodeList(contactService.listFriends(uid))
            ContactMethod.APPLY -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encode(contactService.apply(uid, readString()!!, readString()))
            }
            ContactMethod.ACCEPT -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encode(contactService.accept(readString()!!))
            }
            ContactMethod.REJECT -> ProtoCodec.withPayload(payload!!) {
                ProtoCodec.encode(contactService.reject(readString()!!))
            }
            ContactMethod.DELETE -> ProtoCodec.withPayload(payload!!) {
                contactService.deleteFriend(uid, readString()!!); ByteArray(0)
            }
            ContactMethod.SET_REMARK -> ProtoCodec.withPayload(payload!!) {
                contactService.setRemark(uid, readString()!!, readString()); ByteArray(0)
            }
            ContactMethod.BLACKLIST -> ProtoCodec.withPayload(payload!!) {
                contactService.blacklist(uid, readString()!!); ByteArray(0)
            }
            ContactMethod.BLACKLIST_REMOVE -> ProtoCodec.withPayload(payload!!) {
                contactService.removeFromBlacklist(uid, readString()!!); ByteArray(0)
            }
            ContactMethod.LIST_APPLIES -> ProtoCodec.encodeList(contactService.listPendingApplies(uid))
            ContactMethod.LIST_BLACKLIST -> ProtoCodec.encodeList(contactService.listBlacklist(uid))
        }
    }
}
