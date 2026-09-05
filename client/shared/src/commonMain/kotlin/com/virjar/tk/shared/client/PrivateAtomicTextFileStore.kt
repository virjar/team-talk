package com.virjar.tk.shared.client

import java.io.File

/**
 * 目前由崩溃 ownership 共享、将来可被其他已认证无头/客户端存储复用的最小私有文件原语。实现对
 * 不安全的现有路径按失败关闭处理。
 */
internal interface PrivateAtomicTextFileStore {
    fun existsNonEmpty(): Boolean
    fun readText(): String? = readText(Int.MAX_VALUE.toLong())
    fun readText(maxBytes: Long): String?
    fun replaceText(content: String) = replaceText(content, Int.MAX_VALUE.toLong())
    fun replaceText(content: String, maxBytes: Long)
    fun cleanupPendingReplacement(): Boolean = false
    fun delete(): Boolean
}

internal expect fun privateAtomicTextFileStore(
    dataDir: File,
    privateDirectories: List<String>,
    fileName: String,
    replacementTemporaryFileName: String? = null,
): PrivateAtomicTextFileStore
