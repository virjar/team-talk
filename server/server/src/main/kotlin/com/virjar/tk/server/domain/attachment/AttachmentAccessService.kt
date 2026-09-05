package com.virjar.tk.server.domain.attachment

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.server.domain.chat.ChatAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 授权附件读取，不向 HTTP 适配器暴露存储细节。 */
fun interface AttachmentAccess {
    suspend fun canRead(uid: String, path: String): Boolean

    /** 在同一聊天成员快照下解析并使用受保护的附件。 */
    suspend fun <T> readAuthorized(uid: String, path: String, block: (canonicalPath: String) -> T): T? =
        if (canRead(uid, path)) block(path) else null
}

/**
 * 上传者可以在发送附件之前读取它。一旦消息提交，被引用聊天的每个当前成员都可以读取它。
 * 随机路径不是一种授权机制。
 */
class AttachmentAccessService(
    private val attachments: AttachmentCatalog,
    private val references: AttachmentReferences,
    private val chats: ChatAccess,
    private val documents: DocumentAttachmentAccess = DocumentAttachmentAccess { _, _ -> false },
    private val userAvatars: UserAvatarReferences = UserAvatarReferences { emptySet() },
) : AttachmentAccess {
    override suspend fun canRead(uid: String, path: String): Boolean =
        readAuthorized(uid, path) { true } == true

    override suspend fun <T> readAuthorized(
        uid: String,
        path: String,
        block: (canonicalPath: String) -> T,
    ): T? = withContext(Dispatchers.IO) {
        val canonicalPath = try {
            AttachmentPolicy.canonicalPath(path)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        if (attachments.getAttachment(canonicalPath) == null) return@withContext null
        // 当前的个人头像对任何已认证的 TeamTalk 用户都可见。HTTP 适配器在进入本服务之前
        // 先认证 uid；一旦个人资料行被替换或清空，旧头像快照就会立即失去这项授权。
        if (userAvatars.isCurrentAvatar(canonicalPath)) return@withContext block(canonicalPath)
        // 上传者只拥有暂存窗口期。一旦任何持久化业务对象绑定了此路径，该对象的实时 ACL
        // 就成为了权威，之后的撤回必须生效。
        val isReferenced = references.isReferenced(canonicalPath)
        if (
            !isReferenced &&
            attachments.isStaging(canonicalPath) &&
            attachments.getOwnerUid(canonicalPath) == uid
        ) {
            return@withContext block(canonicalPath)
        }
        if (documents.canRead(uid, canonicalPath)) return@withContext block(canonicalPath)
        chats.readAccessibleChatIds(uid) { allowedChatIds ->
            if (references.isReferencedByAny(canonicalPath, allowedChatIds)) {
                block(canonicalPath)
            } else {
                null
            }
        }
    }
}
