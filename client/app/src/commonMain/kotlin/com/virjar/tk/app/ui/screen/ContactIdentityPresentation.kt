package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.User

/** 备注属于当前账号的联系人投影，绝不写进共享的 User.name。 */
fun contactDisplayName(user: User?, remark: String?, fallback: String): String =
    remark?.takeIf(String::isNotBlank)
        ?: user?.name?.takeIf(String::isNotBlank)
        ?: user?.username?.takeIf(String::isNotBlank)
        ?: fallback

fun contactRemarks(contacts: List<Contact>): Map<String, String> = buildMap {
    contacts.forEach { contact -> contact.remark?.takeIf(String::isNotBlank)?.let { put(contact.friendUid, it) } }
}
