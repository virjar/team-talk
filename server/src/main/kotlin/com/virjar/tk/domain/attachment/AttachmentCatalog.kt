package com.virjar.tk.domain.attachment

import com.virjar.tk.model.Attachment

/** Read-only authoritative attachment metadata boundary used by message validation. */
interface AttachmentCatalog {
    fun getAttachment(path: String): Attachment?
}
