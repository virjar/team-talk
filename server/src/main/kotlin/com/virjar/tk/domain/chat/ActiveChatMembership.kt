package com.virjar.tk.domain.chat

/** Current, non-deleted chat membership used by cross-domain read authorization. */
fun interface ActiveChatMembership {
    fun listUserChatIds(uid: String): Set<String>
}
