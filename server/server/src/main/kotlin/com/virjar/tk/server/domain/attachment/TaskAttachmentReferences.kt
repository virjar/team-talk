package com.virjar.tk.server.domain.attachment

/** 任务独立于聊天成员资格保留素材；读取按当前任务参与者判定。 */
interface TaskAttachmentReferences {
    fun getReferencedPaths(paths: Set<String>): Set<String>
    suspend fun canRead(uid: String, path: String): Boolean
}
