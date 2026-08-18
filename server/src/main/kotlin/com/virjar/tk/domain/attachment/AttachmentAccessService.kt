package com.virjar.tk.domain.attachment

import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.domain.chat.ActiveChatMembership
import com.virjar.tk.domain.message.MessageRepository

/** Authorizes attachment reads without exposing storage details to the HTTP adapter. */
fun interface AttachmentAccess {
    fun canRead(uid: String, path: String): Boolean
}

/**
 * An uploader may read an attachment before sending it. Once a message commits,
 * every current member of a referenced chat may read it. Random paths are not an
 * authorization mechanism.
 */
class AttachmentAccessService(
    private val attachments: AttachmentCatalog,
    private val messages: MessageRepository,
    private val memberships: ActiveChatMembership,
) : AttachmentAccess {
    override fun canRead(uid: String, path: String): Boolean {
        val canonicalPath = runCatching { AttachmentPolicy.canonicalPath(path) }.getOrNull() ?: return false
        if (attachments.getAttachment(canonicalPath) == null) return false
        if (attachments.getOwnerUid(canonicalPath) == uid) return true
        val allowedChatIds = memberships.listUserChatIds(uid)
        return messages.getAttachmentChatIds(canonicalPath).any(allowedChatIds::contains)
    }
}
