package com.virjar.tk.server.domain.attachment

import com.virjar.tk.protocol.model.Attachment

/** 权威的附件元数据与持久化的暂存/发布边界。 */
interface AttachmentCatalog {
    fun getAttachment(path: String): Attachment?
    fun getOwnerUid(path: String): String?

    /** 仅在对象首次提交进任何业务引用之前为真。 */
    fun isStaging(path: String): Boolean = getAttachment(path) != null

    /** 单调的发布标记；实现绝不能把已绑定的对象重新转回暂存状态。 */
    fun markBusinessBound(paths: Collection<String>) = Unit
}

/**
 * PostgreSQL 引用已提交后的发布标记。失败不在事务内上抛，而是返回给调用方
 * 在事务边界外处理（上抛或记录），保证已提交的引用不被发布失败掩盖。
 */
fun AttachmentCatalog.markBusinessBoundDeferred(path: String): Throwable? = try {
    markBusinessBound(listOf(path))
    null
} catch (failure: Exception) {
    failure
}

/** 当前引用若仍停留在 staging（上次发布失败），先晋升为已绑定；失败延迟返回。 */
fun AttachmentCatalog.promoteStagingReference(path: String): Throwable? = try {
    if (isStaging(path)) markBusinessBound(listOf(path))
    null
} catch (failure: Exception) {
    failure
}
