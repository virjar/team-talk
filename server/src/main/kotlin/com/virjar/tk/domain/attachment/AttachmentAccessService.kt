package com.virjar.tk.domain.attachment

import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.domain.chat.ChatAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Authorizes attachment reads without exposing storage details to the HTTP adapter. */
fun interface AttachmentAccess {
    suspend fun canRead(uid: String, path: String): Boolean

    /** Resolve and use a protected attachment under the same chat-membership snapshot. */
    suspend fun <T> readAuthorized(uid: String, path: String, block: (canonicalPath: String) -> T): T? =
        if (canRead(uid, path)) block(path) else null
}

/**
 * An uploader may read an attachment before sending it. Once a message commits,
 * every current member of a referenced chat may read it. Random paths are not an
 * authorization mechanism.
 */
class AttachmentAccessService(
    private val attachments: AttachmentCatalog,
    private val references: AttachmentReferences,
    private val chats: ChatAccess,
) : AttachmentAccess {
    override suspend fun canRead(uid: String, path: String): Boolean =
        readAuthorized(uid, path) { true } == true

    override suspend fun <T> readAuthorized(
        uid: String,
        path: String,
        block: (canonicalPath: String) -> T,
    ): T? = withContext(Dispatchers.IO) {
        val canonicalPath = runCatching { AttachmentPolicy.canonicalPath(path) }.getOrNull()
            ?: return@withContext null
        if (attachments.getAttachment(canonicalPath) == null) return@withContext null
        if (attachments.getOwnerUid(canonicalPath) == uid) return@withContext block(canonicalPath)
        chats.readAccessibleChatIds(uid) { allowedChatIds ->
            if (references.getChatIds(canonicalPath).any(allowedChatIds::contains)) {
                block(canonicalPath)
            } else {
                null
            }
        }
    }
}
