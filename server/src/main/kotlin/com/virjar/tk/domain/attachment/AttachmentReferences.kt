package com.virjar.tk.domain.attachment

/** 返回某个附件被哪些业务空间引用，供统一下载鉴权使用。 */
fun interface AttachmentReferences {
    fun getChatIds(path: String): Set<String>
}
