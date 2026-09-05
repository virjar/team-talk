package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.MessageType

/**
 * 文件附件引用契约。
 *
 * wire 中统一保存 FileStore 的相对 path（`uid/object.ext`）。外部 SDK 可以传
 * `http(s)://host/api/v1/files/<path>` 或 `/api/v1/files/<path>`，发送前会被
 * 归一化为相对 path；任意其他绝对 URL 都会被拒绝。
 *
 * 这里只负责客户端可完成的结构校验。附件是否真实存在只能由服务端 FileStore
 * 权威校验，形成“SDK 先拦格式、服务端再拦存在性”的两层防线。
 */
object AttachmentPolicy {
    private const val FILE_ENDPOINT = "/api/v1/files/"
    const val MAX_REFERENCE_LENGTH = 4_096
    const val MAX_NAME_LENGTH = 512
    const val MAX_CONTENT_TYPE_LENGTH = 255
    /** 请求/存储准入的硬上限；流式传输避免了堆内存物化，但并不能防止磁盘耗尽。 */
    const val MAX_UPLOAD_BYTES: Long = 512L * 1024L * 1024L

    /** 把相对 path/文件端点 URL 归一化为 FileStore 相对 path。 */
    fun canonicalPath(reference: String): String {
        require(reference.length <= MAX_REFERENCE_LENGTH) { "附件地址过长" }
        val value = reference.trim()
        require(value.isNotEmpty()) { "附件地址不能为空" }

        val pathWithSuffix = when {
            value.startsWith(FILE_ENDPOINT) -> value.removePrefix(FILE_ENDPOINT)
            value.startsWith("http://") || value.startsWith("https://") -> {
                val schemeEnd = value.indexOf("://") + 3
                val pathStart = value.indexOf('/', startIndex = schemeEnd)
                require(pathStart >= 0 && value.startsWith(FILE_ENDPOINT, startIndex = pathStart)) {
                    "附件必须使用 TeamTalk 文件端点: $value"
                }
                value.substring(pathStart + FILE_ENDPOINT.length)
            }
            value.contains("://") || value.startsWith('/') ->
                throw IllegalArgumentException("附件地址不是有效的 TeamTalk 文件路径: $value")
            else -> value
        }

        val path = pathWithSuffix.substringBefore('?').substringBefore('#')
        val segments = path.split('/')
        require(path.isNotBlank() && '\\' !in path && segments.none { it.isBlank() || it == "." || it == ".." }) {
            "附件路径非法: $path"
        }
        return path
    }

    /** 校验附件 body 与 messageType，并把所有附件描述符归一化。 */
    fun canonicalize(message: Message): Message {
        val body = message.body
        if (body !is AttachmentBody) {
            require(message.messageType !in attachmentTypeCodes) {
                "附件消息缺少匹配的消息体: messageType=${message.messageType}"
            }
            return message
        }

        require(message.messageType == body.attachmentMessageType.code) {
            "附件消息类型不匹配: body=${body::class.simpleName}, messageType=${message.messageType}"
        }
        return message.copy(
            body = body.withAttachments(
                attachment = canonicalizeDescriptor(body.attachment),
                thumbnail = body.thumbnail?.let(::canonicalizeDescriptor),
            ),
        )
    }

    /** 返回消息引用的全部附件描述符（主文件在前，随后是缩略图）。 */
    fun attachments(message: Message): List<Attachment> = when (val body = message.body) {
        is AttachmentBody -> listOfNotNull(body.attachment, body.thumbnail)
        is RichTextBody -> body.assets.flatMap { it.attachments() }
        is ReplyBody -> body.assets.flatMap { it.attachments() }
        else -> emptyList()
    }

    /** 在持久化或脱离消息单独发送之前，对单个描述符做归一化。 */
    fun canonicalizeDescriptor(attachment: Attachment): Attachment {
        require(attachment.name.isNotBlank() && attachment.name.length <= MAX_NAME_LENGTH) {
            "附件名称不能为空或超过 $MAX_NAME_LENGTH 个字符"
        }
        require(
            attachment.contentType.isNotBlank() && attachment.contentType.length <= MAX_CONTENT_TYPE_LENGTH,
        ) { "附件类型不能为空或超过 $MAX_CONTENT_TYPE_LENGTH 个字符" }
        require(attachment.size in 0L..MAX_UPLOAD_BYTES) {
            "附件大小必须在 0..$MAX_UPLOAD_BYTES 字节内: ${attachment.size}"
        }
        return attachment.copy(path = canonicalPath(attachment.path))
    }

    private val attachmentTypeCodes = setOf(
        MessageType.FILE.code,
        MessageType.IMAGE.code,
        MessageType.VOICE.code,
        MessageType.VIDEO.code,
        MessageType.STICKER.code,
    )
}
