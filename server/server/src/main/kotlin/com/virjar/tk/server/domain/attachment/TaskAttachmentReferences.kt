package com.virjar.tk.server.domain.attachment

/** Tasks retain materials independently of chat membership; reading uses current task participants. */
interface TaskAttachmentReferences {
    fun getReferencedPaths(paths: Set<String>): Set<String>
    suspend fun canRead(uid: String, path: String): Boolean
}
