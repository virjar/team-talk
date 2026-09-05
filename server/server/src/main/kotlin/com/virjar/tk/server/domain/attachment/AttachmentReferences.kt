package com.virjar.tk.server.domain.attachment

/** 返回附件的权威业务引用，供下载鉴权和物理保留决策使用。 */
fun interface AttachmentReferences {
    fun getChatIds(path: String): Set<String>

    /** 授权接缝（seam）：适配器应将其与调用方有界的聊天集合求交集。 */
    fun isReferencedByAny(path: String, chatIds: Set<String>): Boolean =
        chatIds.isNotEmpty() && getChatIds(path).any(chatIds::contains)

    /** 有界的批量接缝；生产实现避免每条路径都开一个数据库事务。 */
    fun getReferencedPaths(paths: Set<String>): Set<String> =
        paths.filterTo(linkedSetOf()) { path -> getChatIds(path).isNotEmpty() }

    /** 全局业务引用检查。这里有意包含非聊天的引用来源。 */
    fun isReferenced(path: String): Boolean = path in getReferencedPaths(setOf(path))
}

/** 已绑定到活跃文档历史的附件的文档空间 READ 策略。 */
fun interface DocumentAttachmentAccess {
    suspend fun canRead(uid: String, path: String): Boolean
}

/** PostgreSQL 文档历史对附件保留与所有者绕过（owner-bypass）决策的贡献。 */
fun interface DocumentAttachmentReferences {
    fun getReferencedPaths(paths: Set<String>): Set<String>
}

/** 当前用户个人资料头像对保留决策与已认证读取的贡献。 */
fun interface UserAvatarReferences {
    fun getReferencedPaths(paths: Set<String>): Set<String>

    fun isCurrentAvatar(path: String): Boolean = path in getReferencedPaths(setOf(path))
}
