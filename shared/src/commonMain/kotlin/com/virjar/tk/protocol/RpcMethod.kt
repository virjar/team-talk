package com.virjar.tk.protocol

/**
 * RPC 方法路由定义。
 * serviceId + methodId 两级路由，客户端和服务端共享。
 */

enum class ServiceId(val id: Int) {
    AUTH(1),
    USER(2),
    CONTACT(3),
    CHAT(4),
    MESSAGE(5),
    CONVERSATION(6),
    DEVICE(7),
    GENERIC(99);  // 通用扩展入口：methodId = ExtensionType.code

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): ServiceId = idMap[id] ?: throw IllegalArgumentException("Unknown ServiceId: $id")
    }
}

enum class AuthMethod(val id: Int) {
    REGISTER(1),
    LOGIN(2),
    LOGOUT(3),
    REFRESH_TOKEN(4),
    UPDATE_PASSWORD(5);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): AuthMethod = idMap[id] ?: throw IllegalArgumentException("Unknown AuthMethod: $id")
    }
}

enum class UserMethod(val id: Int) {
    GET_PROFILE(1),
    UPDATE_PROFILE(2),
    SEARCH(3);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): UserMethod = idMap[id] ?: throw IllegalArgumentException("Unknown UserMethod: $id")
    }
}

enum class ContactMethod(val id: Int) {
    LIST(1),
    APPLY(2),
    ACCEPT(3),
    REJECT(4),
    DELETE(5),
    SET_REMARK(6),
    BLACKLIST(7),
    BLACKLIST_REMOVE(8),
    LIST_APPLIES(9),
    LIST_BLACKLIST(10);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): ContactMethod = idMap[id] ?: throw IllegalArgumentException("Unknown ContactMethod: $id")
    }
}

enum class ChatMethod(val id: Int) {
    CREATE_PERSONAL(1),
    CREATE_GROUP(2),
    GET(3),
    UPDATE(4),
    DELETE(5),
    ADD_MEMBERS(6),
    REMOVE_MEMBERS(7),
    GET_MEMBERS(8),
    TRANSFER_OWNER(9),
    SET_ROLE(10),
    MUTE_MEMBER(11),
    UNMUTE_MEMBER(12),
    MUTE_ALL(13),
    UNMUTE_ALL(14),
    CREATE_INVITE_LINK(15),
    LIST_INVITE_LINKS(16),
    REVOKE_INVITE_LINK(17),
    JOIN_BY_INVITE(18),
    GET_INVITE_INFO(19);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): ChatMethod = idMap[id] ?: throw IllegalArgumentException("Unknown ChatMethod: $id")
    }
}

enum class MessageMethod(val id: Int) {
    GET_HISTORY(1),
    SEARCH(2),
    REVOKE(3),
    EDIT(4),
    FORWARD(5),
    MARK_READ(6);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): MessageMethod = idMap[id] ?: throw IllegalArgumentException("Unknown MessageMethod: $id")
    }
}

enum class ConversationMethod(val id: Int) {
    LIST(1),
    SYNC(2),
    SET_DRAFT(3),
    SET_PIN(4),
    SET_MUTE(5),
    DELETE(6);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): ConversationMethod = idMap[id] ?: throw IllegalArgumentException("Unknown ConversationMethod: $id")
    }
}

enum class DeviceMethod(val id: Int) {
    LIST(1),
    KICK(2);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): DeviceMethod = idMap[id] ?: throw IllegalArgumentException("Unknown DeviceMethod: $id")
    }
}
