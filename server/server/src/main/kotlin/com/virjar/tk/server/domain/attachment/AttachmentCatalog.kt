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
