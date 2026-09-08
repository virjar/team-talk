package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ContentSearchAttachments
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.model.Message
import java.security.MessageDigest

/** 同一权威消息派生的附件名称词项与元数据摘要，不保存另一份附件索引事实。 */
object MessageAttachmentSearchPolicy {
    fun targetId(attachment: Attachment): String = MessageDigest.getInstance("SHA-256")
        .digest(ContentSearchAttachments.canonicalPath(attachment).encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun matches(attachment: Attachment, keyword: String, fileType: Int): Boolean =
        (fileType == ContentSearchRequest.FILE_TYPE_ALL || ContentSearchAttachments.fileType(attachment) == fileType) &&
            attachment.name.lowercase().contains(keyword.lowercase())

    fun manifest(message: Message): String = digest(
        ContentSearchAttachments.attachments(message).sortedBy { it.path }.flatMap {
            listOf(it.path, it.name, it.contentType, it.size.toString())
        },
    )

    fun names(message: Message, fileType: Int): List<String> =
        ContentSearchAttachments.attachments(message)
            .filter { fileType == ContentSearchRequest.FILE_TYPE_ALL || ContentSearchAttachments.fileType(it) == fileType }
            .map { it.name.lowercase() }.distinct().sorted()

    internal fun digest(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.encodeToByteArray()
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
